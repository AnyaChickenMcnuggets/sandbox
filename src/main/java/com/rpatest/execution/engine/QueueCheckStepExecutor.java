package com.rpatest.execution.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rpatest.config.OrchestratorProperties;
import com.rpatest.execution.domain.StepRun;
import com.rpatest.execution.engine.config.QueueCheckStepConfig;
import com.rpatest.orchestrator.dto.ExchangeQueueDto;
import com.rpatest.orchestrator.dto.ExchangeQueueValueDto;
import com.rpatest.orchestrator.dto.QueueItemDerivedStatus;
import com.rpatest.orchestrator.exception.OrchestratorApiException;
import com.rpatest.orchestrator.util.OrchestratorNames;
import com.rpatest.orchestrator.util.OrchestratorNarration;
import com.rpatest.scenario.domain.ScenarioStep;
import com.rpatest.scenario.domain.ScenarioStepType;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Проверяет фактическое состояние транзакций уже существующей очереди против ожиданий автора
 * сценария (сколько должно получиться Success/Error/BusinessError, сколько всего элементов) —
 * единственный надёжный способ узнать, что задание реально обработало данные: статус Assignment
 * ({@link com.rpatest.orchestrator.dto.AssignmentStatus#COMPLETE}) отражает только то, что
 * оркестратор успешно поставил проект в очередь выполнения, а не то, что робот его отработал.
 */
@Component
public class QueueCheckStepExecutor implements StepExecutor {

    private static final Logger log = LoggerFactory.getLogger(QueueCheckStepExecutor.class);

    private final QueueItemFinder queueItemFinder;
    private final ExchangeQueueProvisioner queueProvisioner;
    private final StepProgressReporter progressReporter;
    private final OrchestratorProperties properties;
    private final ObjectMapper objectMapper;

    public QueueCheckStepExecutor(
            QueueItemFinder queueItemFinder,
            ExchangeQueueProvisioner queueProvisioner,
            StepProgressReporter progressReporter,
            OrchestratorProperties properties,
            ObjectMapper objectMapper) {
        this.queueItemFinder = queueItemFinder;
        this.queueProvisioner = queueProvisioner;
        this.progressReporter = progressReporter;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public ScenarioStepType supports() {
        return ScenarioStepType.QUEUE_CHECK;
    }

    @Override
    public void execute(StepRun stepRun, ScenarioStep step) {
        QueueCheckStepConfig config = objectMapper.convertValue(step.getConfig(), QueueCheckStepConfig.class);
        if (config.queueName() == null || config.queueName().isBlank()) {
            throw new StepExecutionException("Не указано имя очереди для проверки в шаге '" + step.getName() + "'");
        }
        String queueName = OrchestratorNames.sanitize(config.queueName());
        Set<String> naturalKeyFilter = new HashSet<>(config.naturalKeysOrEmpty());
        boolean prefixMatch = config.isNaturalKeyPrefixMatch();
        Map<String, Integer> expected = config.expectedStatusCountsOrEmpty();
        Integer minTotalCount = config.minTotalCount();

        OrchestratorProperties.Polling defaults = properties.getQueueCheckPolling();
        Duration interval = config.pollIntervalSeconds() != null
                ? Duration.ofSeconds(config.pollIntervalSeconds()) : defaults.getInterval();
        // null — без таймаута: единственный источник ограничения по времени — config.timeoutSeconds шага
        Duration timeout = config.timeoutSeconds() != null ? Duration.ofSeconds(config.timeoutSeconds()) : null;

        log.info("Шаг '{}' (id={}): проверка очереди '{}', ожидание={}, minTotalCount={}, naturalKeys={}"
                        + " (prefix={}), timeout={}",
                step.getName(), step.getId(), queueName, expected, minTotalCount, naturalKeyFilter, prefixMatch,
                timeout == null ? "не ограничен" : timeout);

        try {
            progressReporter.report(stepRun, "Ищу/создаю очередь '" + queueName + "' для проверки");
            // Get-or-create: если очередь ещё не создана предыдущим шагом (например, DAG собран
            // с QUEUE_CHECK раньше соответствующего QUEUE), проверка не должна падать — просто
            // ждём появления элементов в пустой (только что созданной) очереди до таймаута.
            ExchangeQueueDto queue = queueProvisioner.ensureExists(queueName, null, null, null).queue();
            stepRun.setOrchestratorQueueId(queue.id());
            // QUEUE_CHECK никогда не владеет очередью для целей cleanup — он либо переиспользует,
            // либо (get-or-create) создаёт пустую только чтобы было что поллить, но не "создаёт"
            // её в смысле "это моя очередь, которую можно удалить после прогона".
            progressReporter.report(stepRun, "Очередь '" + queueName + "' (id=" + queue.id()
                    + ") найдена, начинаю проверку. Ожидается: "
                    + OrchestratorNarration.describeExpectation(expected, minTotalCount));

            pollUntilSatisfied(stepRun, queue.id(), queueName, naturalKeyFilter, prefixMatch, expected, minTotalCount,
                    queue.maxRetrayOrZero(), interval, timeout);
        } catch (OrchestratorApiException e) {
            log.error("Шаг '{}': ошибка вызова оркестратора при проверке очереди '{}'", step.getName(), queueName, e);
            throw new StepExecutionException("Не удалось выполнить проверку очереди '" + step.getName() + "'", e);
        }
    }

    private void pollUntilSatisfied(
            StepRun stepRun,
            UUID queueId,
            String queueName,
            Set<String> naturalKeyFilter,
            boolean prefixMatch,
            Map<String, Integer> expected,
            Integer minTotalCount,
            int maxRetray,
            Duration interval,
            Duration timeout) {
        Instant deadline = timeout == null ? null : Instant.now().plus(timeout);
        Map<String, Long> actualCounts;
        int actualTotal;
        int attempt = 0;
        // Если все транзакции, отслеживаемые по naturalKeyFilter, уже застыли в конечном статусе
        // (не NEW/IN_PROGRESS) и этого всё ещё недостаточно — дальше ждать нечего, статус сам по
        // себе больше не поменяется (см. stableUnsatisfiedTerminalCount ниже). Не рубим по первому
        // же такому снимку — реально ещё один опрос (на случай, если список вот-вот пополнится
        // новой транзакцией с тем же префиксом/роботом) и только если СЛЕДУЮЩИЙ снимок повторяет ту
        // же картину (тот же набор, всё так же конечное и всё так же недостаточно) — fail fast
        // вместо ожидания оставшегося timeout впустую.
        boolean previousAttemptWasStableTerminalAndUnsatisfied = false;
        int previousMatchedTotal = -1;
        while (true) {
            attempt++;
            List<ExchangeQueueValueDto> matching = queueItemFinder.find(queueId, naturalKeyFilter, prefixMatch);
            actualCounts = countByStatus(matching, maxRetray);
            actualTotal = matching.size();

            String actualDescription = OrchestratorNarration.describeActual(actualCounts, actualTotal);
            log.debug("Попытка #{} проверки очереди '{}': {}", attempt, queueName, actualDescription);
            progressReporter.report(stepRun, "Проверка очереди '" + queueName + "' (попытка #" + attempt + "): "
                    + actualDescription);

            if (satisfies(expected, minTotalCount, actualCounts, actualTotal)) {
                progressReporter.report(stepRun, "Проверка очереди '" + queueName + "' пройдена: " + actualDescription);
                return;
            }

            boolean allTerminalNow = !naturalKeyFilter.isEmpty() && isAllTerminal(matching, maxRetray);
            if (allTerminalNow && previousAttemptWasStableTerminalAndUnsatisfied && actualTotal == previousMatchedTotal) {
                progressReporter.report(stepRun, "Проверка очереди '" + queueName
                        + "' прекращена досрочно: все отслеживаемые транзакции (" + actualTotal
                        + ") уже в конечном статусе, дальнейшее ожидание бессмысленно");
                throw new StepExecutionException("Проверка очереди '" + queueName
                        + "' не пройдена: все отслеживаемые транзакции уже получили конечный статус, "
                        + "который не изменится. " + OrchestratorNarration.describeCheckResult(
                                expected, minTotalCount, actualCounts, actualTotal));
            }
            previousAttemptWasStableTerminalAndUnsatisfied = allTerminalNow;
            previousMatchedTotal = actualTotal;

            if (deadline != null && Instant.now().isAfter(deadline)) {
                throw new StepExecutionException("Проверка очереди '" + queueName
                        + "' не прошла за отведённое время. "
                        + OrchestratorNarration.describeCheckResult(expected, minTotalCount, actualCounts, actualTotal));
            }
            sleep(interval);
        }
    }

    private Map<String, Long> countByStatus(List<ExchangeQueueValueDto> items, int maxRetray) {
        return items.stream()
                .collect(Collectors.groupingBy(i -> effectiveStatus(i, maxRetray).name(), Collectors.counting()));
    }

    /**
     * Оркестратор при статусе Error автоматически перекладывает транзакцию обратно в очередь для
     * повторной попытки, пока не исчерпан лимит повторов очереди ({@code ExchangeQueueDto.maxRetray}).
     * Пока item.retray() < maxRetray, текущий Error — ещё не финальный результат: транзакцию
     * возьмут в обработку снова, и статус может ещё смениться на Success. Считаем такую транзакцию
     * как IN_PROGRESS (ожидает повтора), а не как ERROR — иначе QUEUE_CHECK может зафиксировать
     * "ошибку", которую оркестратор через секунду сам исправит повторной попыткой.
     */
    private QueueItemDerivedStatus effectiveStatus(ExchangeQueueValueDto item, int maxRetray) {
        QueueItemDerivedStatus status = item.derivedStatus();
        if (status == QueueItemDerivedStatus.ERROR && item.retrayOrZero() < maxRetray) {
            return QueueItemDerivedStatus.IN_PROGRESS;
        }
        return status;
    }

    /** {@code true}, если ни одна из переданных транзакций не может ещё сменить статус сама по
     * себе (нет ни {@code NEW}, ни {@code IN_PROGRESS}/недоисчерпанного {@code ERROR}) — пустой
     * список НЕ считается "всё готово": транзакции могли просто ещё не появиться в очереди. */
    private boolean isAllTerminal(List<ExchangeQueueValueDto> items, int maxRetray) {
        if (items.isEmpty()) {
            return false;
        }
        return items.stream().allMatch(item -> {
            QueueItemDerivedStatus status = effectiveStatus(item, maxRetray);
            return status != QueueItemDerivedStatus.NEW && status != QueueItemDerivedStatus.IN_PROGRESS;
        });
    }

    /**
     * {@code expectedStatusCounts} — это минимум по каждому статусу, а не точное совпадение:
     * проверка проходит, когда фактическое количество **не меньше** ожидаемого. Оркестратор может
     * дообработать транзакции уже после того, как ожидание было достигнуто (например, довыполнить
     * ещё несколько запусков из связанного `RunStatuses` фильтра или досчитать повторы), и жёсткое
     * равенство заставляло бы шаг падать по гонке между моментом опроса и моментом достижения
     * искомого количества — хотя сценарий автора уже выполнился так, как ожидалось.
     */
    private boolean satisfies(
            Map<String, Integer> expected, Integer minTotalCount, Map<String, Long> actualCounts, int actualTotal) {
        if (minTotalCount != null && actualTotal < minTotalCount) {
            return false;
        }
        for (Map.Entry<String, Integer> entry : expected.entrySet()) {
            long actual = actualCounts.getOrDefault(entry.getKey(), 0L);
            if (actual < entry.getValue()) {
                return false;
            }
        }
        return true;
    }

    private void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StepExecutionException("Ожидание проверки очереди было прервано", e);
        }
    }
}
