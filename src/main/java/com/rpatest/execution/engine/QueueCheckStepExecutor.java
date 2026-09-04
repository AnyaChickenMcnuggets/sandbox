package com.rpatest.execution.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rpatest.config.OrchestratorProperties;
import com.rpatest.execution.domain.StepRun;
import com.rpatest.execution.engine.config.QueueCheckStepConfig;
import com.rpatest.orchestrator.client.ExchangeQueuesPort;
import com.rpatest.orchestrator.dto.ExchangeQueueDto;
import com.rpatest.orchestrator.dto.ExchangeQueueValueDto;
import com.rpatest.orchestrator.exception.OrchestratorApiException;
import com.rpatest.orchestrator.util.OrchestratorNames;
import com.rpatest.scenario.domain.ScenarioStep;
import com.rpatest.scenario.domain.ScenarioStepType;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
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

    private static final int PAGE_SIZE = 200;
    private static final int MAX_PAGES = 50;

    private final ExchangeQueuesPort exchangeQueuesPort;
    private final ExchangeQueueProvisioner queueProvisioner;
    private final OrchestratorProperties properties;
    private final ObjectMapper objectMapper;

    public QueueCheckStepExecutor(
            ExchangeQueuesPort exchangeQueuesPort,
            ExchangeQueueProvisioner queueProvisioner,
            OrchestratorProperties properties,
            ObjectMapper objectMapper) {
        this.exchangeQueuesPort = exchangeQueuesPort;
        this.queueProvisioner = queueProvisioner;
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
        Duration timeout = config.timeoutSeconds() != null
                ? Duration.ofSeconds(config.timeoutSeconds()) : defaults.getTimeout();

        try {
            // Get-or-create: если очередь ещё не создана предыдущим шагом (например, DAG собран
            // с QUEUE_CHECK раньше соответствующего QUEUE), проверка не должна падать — просто
            // ждём появления элементов в пустой (только что созданной) очереди до таймаута.
            ExchangeQueueDto queue = queueProvisioner.ensureExists(queueName, null, null, null);
            stepRun.setOrchestratorQueueId(queue.id());

            pollUntilSatisfied(
                    queue.id(), queueName, naturalKeyFilter, prefixMatch, expected, minTotalCount, interval, timeout);
        } catch (OrchestratorApiException e) {
            throw new StepExecutionException("Не удалось выполнить проверку очереди '" + step.getName() + "'", e);
        }
    }

    private void pollUntilSatisfied(
            UUID queueId,
            String queueName,
            Set<String> naturalKeyFilter,
            boolean prefixMatch,
            Map<String, Integer> expected,
            Integer minTotalCount,
            Duration interval,
            Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        Map<String, Long> actualCounts;
        int actualTotal;
        while (true) {
            List<ExchangeQueueValueDto> matching = fetchMatchingItems(queueId, naturalKeyFilter, prefixMatch);
            actualCounts = countByStatus(matching);
            actualTotal = matching.size();

            if (satisfies(expected, minTotalCount, actualCounts, actualTotal)) {
                return;
            }
            if (Instant.now().isAfter(deadline)) {
                throw new StepExecutionException("Проверка очереди '" + queueName
                        + "' не прошла за отведённое время. " + describe(expected, minTotalCount, actualCounts, actualTotal));
            }
            sleep(interval);
        }
    }

    private List<ExchangeQueueValueDto> fetchMatchingItems(UUID queueId, Set<String> naturalKeyFilter, boolean prefixMatch) {
        List<ExchangeQueueValueDto> all = new ArrayList<>();
        for (int page = 0; page < MAX_PAGES; page++) {
            List<ExchangeQueueValueDto> items = exchangeQueuesPort.listItems(queueId, page, PAGE_SIZE).result();
            if (items == null || items.isEmpty()) {
                break;
            }
            all.addAll(items);
            if (items.size() < PAGE_SIZE) {
                break;
            }
        }
        if (naturalKeyFilter.isEmpty()) {
            return all;
        }
        if (prefixMatch) {
            return all.stream()
                    .filter(i -> i.naturalKey() != null
                            && naturalKeyFilter.stream().anyMatch(prefix -> i.naturalKey().startsWith(prefix)))
                    .toList();
        }
        return all.stream().filter(i -> i.naturalKey() != null && naturalKeyFilter.contains(i.naturalKey())).toList();
    }

    private Map<String, Long> countByStatus(List<ExchangeQueueValueDto> items) {
        return items.stream().collect(Collectors.groupingBy(i -> i.derivedStatus().name(), Collectors.counting()));
    }

    private boolean satisfies(
            Map<String, Integer> expected, Integer minTotalCount, Map<String, Long> actualCounts, int actualTotal) {
        if (minTotalCount != null && actualTotal < minTotalCount) {
            return false;
        }
        for (Map.Entry<String, Integer> entry : expected.entrySet()) {
            long actual = actualCounts.getOrDefault(entry.getKey(), 0L);
            if (actual != entry.getValue()) {
                return false;
            }
        }
        return true;
    }

    private String describe(
            Map<String, Integer> expected, Integer minTotalCount, Map<String, Long> actualCounts, int actualTotal) {
        StringBuilder sb = new StringBuilder("Ожидалось: ");
        expected.forEach((status, count) -> sb.append(status).append("=").append(count).append(" "));
        if (minTotalCount != null) {
            sb.append("(всего >= ").append(minTotalCount).append(") ");
        }
        sb.append("— фактически: всего=").append(actualTotal).append(" ");
        actualCounts.forEach((status, count) -> sb.append(status).append("=").append(count).append(" "));
        return sb.toString();
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
