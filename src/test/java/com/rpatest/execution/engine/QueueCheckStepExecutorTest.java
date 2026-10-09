package com.rpatest.execution.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rpatest.config.OrchestratorProperties;
import com.rpatest.execution.domain.StepRun;
import com.rpatest.execution.repository.StepRunRepository;
import com.rpatest.orchestrator.client.ExchangeQueuesPort;
import com.rpatest.orchestrator.dto.ExchangeQueueDto;
import com.rpatest.orchestrator.dto.ExchangeQueueValueDto;
import com.rpatest.orchestrator.dto.ExchangeQueueValueEventType;
import com.rpatest.orchestrator.dto.ListResultDto;
import com.rpatest.orchestrator.exception.OrchestratorApiException;
import com.rpatest.scenario.domain.ScenarioStep;
import com.rpatest.scenario.domain.ScenarioStepType;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class QueueCheckStepExecutorTest {

    private ExchangeQueuesPort exchangeQueuesPort;
    private QueueCheckStepExecutor executor;

    @BeforeEach
    void setUp() {
        exchangeQueuesPort = mock(ExchangeQueuesPort.class);
        OrchestratorProperties properties = new OrchestratorProperties();
        properties.getQueueCheckPolling().setInterval(Duration.ofMillis(10));
        StepProgressReporter progressReporter = new StepProgressReporter(mock(StepRunRepository.class));
        executor = new QueueCheckStepExecutor(new QueueItemFinder(exchangeQueuesPort),
                new ExchangeQueueProvisioner(exchangeQueuesPort), progressReporter, properties, new ObjectMapper());
    }

    @Test
    void supportsQueueCheckType() {
        assertThat(executor.supports()).isEqualTo(ScenarioStepType.QUEUE_CHECK);
    }

    @Test
    void succeedsWhenExpectedStatusCountsMatch() {
        UUID queueId = UUID.randomUUID();
        when(exchangeQueuesPort.findByName("q")).thenReturn(Optional.of(new ExchangeQueueDto(queueId, "q", null, 0, 0, null)));
        when(exchangeQueuesPort.listItems(queueId, 0, 200)).thenReturn(ListResultDto.<ExchangeQueueValueDto>of(2, List.of(
                item("k1", ExchangeQueueValueEventType.SUCCESS),
                item("k2", ExchangeQueueValueEventType.ERROR))));

        ScenarioStep step = step(Map.of(
                "queueName", "q",
                "expectedStatusCounts", Map.of("SUCCESS", 1, "ERROR", 1)));
        StepRun stepRun = new StepRun(1L, 2L);

        executor.execute(stepRun, step);

        assertThat(stepRun.getOrchestratorQueueId()).isEqualTo(queueId);
    }

    @Test
    void excludesDeletedTransactionsFromCounts() {
        // удалённая транзакция (deletedAt != null) не должна влиять на исход проверки — иначе
        // ручное или автоматическое удаление элемента из очереди искажает результат
        UUID queueId = UUID.randomUUID();
        when(exchangeQueuesPort.findByName("q")).thenReturn(Optional.of(new ExchangeQueueDto(queueId, "q", null, 0, 0, null)));
        when(exchangeQueuesPort.listItems(queueId, 0, 200)).thenReturn(ListResultDto.<ExchangeQueueValueDto>of(2, List.of(
                item("k1", ExchangeQueueValueEventType.SUCCESS),
                deletedItem("k2", ExchangeQueueValueEventType.SUCCESS))));

        ScenarioStep step = step(Map.of(
                "queueName", "q",
                "expectedStatusCounts", Map.of("SUCCESS", 1)));
        StepRun stepRun = new StepRun(1L, 2L);

        executor.execute(stepRun, step);
    }

    @Test
    void filtersByNaturalKeysWhenProvided() {
        // непустой naturalKeys — поиск идёт по фильтру оркестратора (NaturalKey/NaturalKeyPart),
        // а не постраничным перебором всей очереди, поэтому мок отвечает именно на вызов с
        // фильтром, а не на "голый" listItems(queueId, page, size)
        UUID queueId = UUID.randomUUID();
        when(exchangeQueuesPort.findByName("q")).thenReturn(Optional.of(new ExchangeQueueDto(queueId, "q", null, 0, 0, null)));
        when(exchangeQueuesPort.listItems(queueId, 0, 200, "tracked", false)).thenReturn(
                ListResultDto.<ExchangeQueueValueDto>of(1, List.of(item("tracked", ExchangeQueueValueEventType.SUCCESS))));

        ScenarioStep step = step(Map.of(
                "queueName", "q",
                "naturalKeys", List.of("tracked"),
                "expectedStatusCounts", Map.of("SUCCESS", 1)));
        StepRun stepRun = new StepRun(1L, 2L);

        executor.execute(stepRun, step);
    }

    @Test
    void matchesNaturalKeyByPrefixWhenEnabled() {
        // один вход (naturalKey "tx-1") может породить несколько выходных транзакций с тем же
        // базовым ключом и дописанным суффиксом для трассировки: "tx-1-a", "tx-1-b". Мок также
        // возвращает заведомо непредназначенный "tx-2-a" — проверяем, что финальный точный
        // client-side re-check (startsWith) сам отсекает лишнее, даже если бы фильтр оркестратора
        // оказался мягче ожидаемого (документации на точную семантику NaturalKeyPart нет).
        UUID queueId = UUID.randomUUID();
        when(exchangeQueuesPort.findByName("q")).thenReturn(Optional.of(new ExchangeQueueDto(queueId, "q", null, 0, 0, null)));
        when(exchangeQueuesPort.listItems(queueId, 0, 200, "tx-1", true)).thenReturn(ListResultDto.<ExchangeQueueValueDto>of(3, List.of(
                item("tx-1-a", ExchangeQueueValueEventType.SUCCESS),
                item("tx-1-b", ExchangeQueueValueEventType.SUCCESS),
                item("tx-2-a", ExchangeQueueValueEventType.SUCCESS))));

        ScenarioStep step = step(Map.of(
                "queueName", "q",
                "naturalKeys", List.of("tx-1"),
                "naturalKeyPrefixMatch", true,
                "expectedStatusCounts", Map.of("SUCCESS", 2)));
        StepRun stepRun = new StepRun(1L, 2L);

        executor.execute(stepRun, step);
    }

    @Test
    void doesNotPrefixMatchWhenFlagIsAbsent() {
        // без naturalKeyPrefixMatch=true "tx-1" не должен матчить "tx-1-a" — точное совпадение,
        // даже если сервер (мок здесь заведомо "мягкий") вернул частичное совпадение
        UUID queueId = UUID.randomUUID();
        when(exchangeQueuesPort.findByName("q")).thenReturn(Optional.of(new ExchangeQueueDto(queueId, "q", null, 0, 0, null)));
        when(exchangeQueuesPort.listItems(queueId, 0, 200, "tx-1", false)).thenReturn(ListResultDto.<ExchangeQueueValueDto>of(1, List.of(
                item("tx-1-a", ExchangeQueueValueEventType.SUCCESS))));

        ScenarioStep step = step(Map.of(
                "queueName", "q",
                "naturalKeys", List.of("tx-1"),
                "timeoutSeconds", 1,
                "expectedStatusCounts", Map.of("SUCCESS", 1)));
        StepRun stepRun = new StepRun(1L, 2L);

        assertThatThrownBy(() -> executor.execute(stepRun, step)).isInstanceOf(StepExecutionException.class);
    }

    @Test
    void queriesEachNaturalKeySeparatelyWhenMultipleProvided() {
        // несколько ожидаемых ключей — отдельный отфильтрованный запрос на каждый, а не один
        // "выгрузить всё и отфильтровать локально" (та самая жалоба — тысячи транзакций в очереди
        // при поиске нескольких конкретных ключей)
        UUID queueId = UUID.randomUUID();
        when(exchangeQueuesPort.findByName("q")).thenReturn(Optional.of(new ExchangeQueueDto(queueId, "q", null, 0, 0, null)));
        when(exchangeQueuesPort.listItems(queueId, 0, 200, "k1", false)).thenReturn(
                ListResultDto.<ExchangeQueueValueDto>of(1, List.of(item("k1", ExchangeQueueValueEventType.SUCCESS))));
        when(exchangeQueuesPort.listItems(queueId, 0, 200, "k2", false)).thenReturn(
                ListResultDto.<ExchangeQueueValueDto>of(1, List.of(item("k2", ExchangeQueueValueEventType.SUCCESS))));

        ScenarioStep step = step(Map.of(
                "queueName", "q",
                "naturalKeys", List.of("k1", "k2"),
                "expectedStatusCounts", Map.of("SUCCESS", 2)));
        StepRun stepRun = new StepRun(1L, 2L);

        executor.execute(stepRun, step);

        verify(exchangeQueuesPort).listItems(queueId, 0, 200, "k1", false);
        verify(exchangeQueuesPort).listItems(queueId, 0, 200, "k2", false);
    }

    @Test
    void succeedsWhenMinTotalCountSatisfied() {
        UUID queueId = UUID.randomUUID();
        when(exchangeQueuesPort.findByName("q")).thenReturn(Optional.of(new ExchangeQueueDto(queueId, "q", null, 0, 0, null)));
        when(exchangeQueuesPort.listItems(queueId, 0, 200)).thenReturn(ListResultDto.<ExchangeQueueValueDto>of(3, List.of(
                item("k1", null), item("k2", null), item("k3", null))));

        ScenarioStep step = step(Map.of("queueName", "q", "minTotalCount", 3));
        StepRun stepRun = new StepRun(1L, 2L);

        executor.execute(stepRun, step);
    }

    @Test
    void throwsOnTimeoutWhenExpectationsNeverMet() {
        UUID queueId = UUID.randomUUID();
        when(exchangeQueuesPort.findByName("q")).thenReturn(Optional.of(new ExchangeQueueDto(queueId, "q", null, 0, 0, null)));
        when(exchangeQueuesPort.listItems(queueId, 0, 200)).thenReturn(ListResultDto.<ExchangeQueueValueDto>of(1, List.of(item("k1", null))));

        ScenarioStep step = step(Map.of(
                "queueName", "q", "timeoutSeconds", 1, "expectedStatusCounts", Map.of("SUCCESS", 5)));
        StepRun stepRun = new StepRun(1L, 2L);

        assertThatThrownBy(() -> executor.execute(stepRun, step))
                .isInstanceOf(StepExecutionException.class)
                .hasMessageContaining("SUCCESS>=5");
    }

    @Test
    void withoutTimeoutSecondsWaitsPastFormerDefaultAndSucceedsWhenItemsAppear() {
        // timeoutSeconds не указан — никакого скрытого дедлайна: 30 опросов с пустым результатом
        // (дольше, чем любой тестовый таймаут) не роняют шаг, он дожидается появления элемента
        UUID queueId = UUID.randomUUID();
        when(exchangeQueuesPort.findByName("q")).thenReturn(Optional.of(new ExchangeQueueDto(queueId, "q", null, 0, 0, null)));
        org.mockito.stubbing.OngoingStubbing<ListResultDto<ExchangeQueueValueDto>> stub =
                when(exchangeQueuesPort.listItems(queueId, 0, 200));
        for (int i = 0; i < 30; i++) {
            stub = stub.thenReturn(ListResultDto.<ExchangeQueueValueDto>of(0, List.of()));
        }
        stub.thenReturn(ListResultDto.<ExchangeQueueValueDto>of(1, List.of(item("k1", ExchangeQueueValueEventType.SUCCESS))));

        ScenarioStep step = step(Map.of("queueName", "q", "minTotalCount", 1));
        StepRun stepRun = new StepRun(1L, 2L);

        executor.execute(stepRun, step);

        verify(exchangeQueuesPort, org.mockito.Mockito.atLeast(31)).listItems(queueId, 0, 200);
    }

    @Test
    void succeedsWhenActualCountExceedsExpected() {
        // expectedStatusCounts — минимум, а не точное совпадение: больше ожидаемого тоже проходит
        UUID queueId = UUID.randomUUID();
        when(exchangeQueuesPort.findByName("q")).thenReturn(Optional.of(new ExchangeQueueDto(queueId, "q", null, 0, 0, null)));
        when(exchangeQueuesPort.listItems(queueId, 0, 200)).thenReturn(ListResultDto.<ExchangeQueueValueDto>of(3, List.of(
                item("k1", ExchangeQueueValueEventType.SUCCESS),
                item("k2", ExchangeQueueValueEventType.SUCCESS),
                item("k3", ExchangeQueueValueEventType.SUCCESS))));

        ScenarioStep step = step(Map.of("queueName", "q", "expectedStatusCounts", Map.of("SUCCESS", 1)));
        StepRun stepRun = new StepRun(1L, 2L);

        executor.execute(stepRun, step);
    }

    @Test
    void createsMissingQueueInsteadOfFailingImmediately() {
        // get-or-create: DAG может быть собран так, что QUEUE_CHECK выполняется раньше
        // соответствующего QUEUE-шага — тогда проверяем пустую, только что созданную очередь,
        // а не падаем сразу с "не найдена".
        UUID queueId = UUID.randomUUID();
        when(exchangeQueuesPort.findByName("missing"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(new ExchangeQueueDto(queueId, "missing", null, 0, 0, null)));
        when(exchangeQueuesPort.listItems(queueId, 0, 200)).thenReturn(ListResultDto.<ExchangeQueueValueDto>of(0, List.of()));

        ScenarioStep step = step(Map.of("queueName", "missing", "minTotalCount", 0));
        StepRun stepRun = new StepRun(1L, 2L);

        executor.execute(stepRun, step);

        verify(exchangeQueuesPort).create(any());
        assertThat(stepRun.getOrchestratorQueueId()).isEqualTo(queueId);
    }

    @Test
    void throwsWhenQueueStillNotFoundAfterCreateAttempt() {
        when(exchangeQueuesPort.findByName("missing")).thenReturn(Optional.empty());

        ScenarioStep step = step(Map.of("queueName", "missing"));
        StepRun stepRun = new StepRun(1L, 2L);

        assertThatThrownBy(() -> executor.execute(stepRun, step)).isInstanceOf(StepExecutionException.class);
    }

    @Test
    void doesNotRecreateQueueThatAlreadyExists() {
        UUID queueId = UUID.randomUUID();
        when(exchangeQueuesPort.findByName("q")).thenReturn(Optional.of(new ExchangeQueueDto(queueId, "q", null, 0, 0, null)));
        when(exchangeQueuesPort.listItems(queueId, 0, 200)).thenReturn(ListResultDto.<ExchangeQueueValueDto>of(0, List.of()));

        ScenarioStep step = step(Map.of("queueName", "q", "minTotalCount", 0));
        StepRun stepRun = new StepRun(1L, 2L);

        executor.execute(stepRun, step);

        verify(exchangeQueuesPort, never()).create(any());
    }

    @Test
    void errorNotYetExhaustingQueueRetryLimitIsNotCountedAsFinalError() {
        // очередь допускает до 3 повторов при Error — оркестратор сам переложит транзакцию для
        // повторной попытки, значит текущий Error ещё не финальный результат
        UUID queueId = UUID.randomUUID();
        when(exchangeQueuesPort.findByName("q")).thenReturn(Optional.of(new ExchangeQueueDto(queueId, "q", null, 0, 0, 3)));
        when(exchangeQueuesPort.listItems(queueId, 0, 200)).thenReturn(ListResultDto.<ExchangeQueueValueDto>of(1, List.of(
                errorItem("k1", 1))));

        ScenarioStep step = step(Map.of("queueName", "q", "expectedStatusCounts", Map.of("ERROR", 0)));
        StepRun stepRun = new StepRun(1L, 2L);

        executor.execute(stepRun, step);
    }

    @Test
    void errorAtOrBeyondQueueRetryLimitCountsAsFinalError() {
        UUID queueId = UUID.randomUUID();
        when(exchangeQueuesPort.findByName("q")).thenReturn(Optional.of(new ExchangeQueueDto(queueId, "q", null, 0, 0, 3)));
        when(exchangeQueuesPort.listItems(queueId, 0, 200)).thenReturn(ListResultDto.<ExchangeQueueValueDto>of(1, List.of(
                errorItem("k1", 3))));

        ScenarioStep step = step(Map.of("queueName", "q", "expectedStatusCounts", Map.of("ERROR", 1)));
        StepRun stepRun = new StepRun(1L, 2L);

        executor.execute(stepRun, step);
    }

    @Test
    void exitsEarlyWhenAllTrackedTransactionsReachStableTerminalStatus() {
        // k1 уже SUCCESS и никогда не станет ещё раз SUCCESS (expectedStatusCounts требует 5) —
        // ждать полный timeout бессмысленно, должны упасть после одного подтверждающего опроса
        UUID queueId = UUID.randomUUID();
        when(exchangeQueuesPort.findByName("q")).thenReturn(Optional.of(new ExchangeQueueDto(queueId, "q", null, 0, 0, null)));
        when(exchangeQueuesPort.listItems(queueId, 0, 200, "k1", false)).thenReturn(
                ListResultDto.<ExchangeQueueValueDto>of(1, List.of(item("k1", ExchangeQueueValueEventType.SUCCESS))));

        ScenarioStep step = step(Map.of(
                "queueName", "q",
                "naturalKeys", List.of("k1"),
                "expectedStatusCounts", Map.of("SUCCESS", 5)));
        StepRun stepRun = new StepRun(1L, 2L);

        assertThatThrownBy(() -> executor.execute(stepRun, step))
                .isInstanceOf(StepExecutionException.class)
                .hasMessageContaining("already have a final status");

        // один опрос, показавший "всё конечное и недостаточно", + один подтверждающий — не 15,
        // которые набежали бы за timeout=150ms/interval=10ms без досрочного выхода
        verify(exchangeQueuesPort, org.mockito.Mockito.times(2)).listItems(queueId, 0, 200, "k1", false);
    }

    @Test
    void doesNotExitEarlyWhenWatchingWholeQueueWithoutNaturalKeys() {
        // без naturalKeys (watch всей очереди через minTotalCount) список не закрыт — новые
        // транзакции могут появиться в любой момент, поэтому "все текущие уже конечные" не повод
        // переставать ждать — должны честно досидеть до timeout, как раньше
        UUID queueId = UUID.randomUUID();
        when(exchangeQueuesPort.findByName("q")).thenReturn(Optional.of(new ExchangeQueueDto(queueId, "q", null, 0, 0, null)));
        when(exchangeQueuesPort.listItems(queueId, 0, 200)).thenReturn(ListResultDto.<ExchangeQueueValueDto>of(1, List.of(
                item("k1", ExchangeQueueValueEventType.SUCCESS))));

        ScenarioStep step = step(Map.of("queueName", "q", "minTotalCount", 5, "timeoutSeconds", 1));
        StepRun stepRun = new StepRun(1L, 2L);

        assertThatThrownBy(() -> executor.execute(stepRun, step))
                .isInstanceOf(StepExecutionException.class)
                .hasMessageContaining("did not pass within the allotted time");

        verify(exchangeQueuesPort, org.mockito.Mockito.atLeast(5)).listItems(queueId, 0, 200);
    }

    @Test
    void doesNotExitEarlyWhenNewTrackedTransactionAppearsBetweenPolls() {
        // k2 появляется только со второго опроса — первая "стабильная" пара (1-й и 2-й опрос)
        // размер набора не совпадает, значит это НЕ стабильная картина, fail-fast не срабатывает
        // раньше, чем набор дважды подряд не поменяется
        UUID queueId = UUID.randomUUID();
        when(exchangeQueuesPort.findByName("q")).thenReturn(Optional.of(new ExchangeQueueDto(queueId, "q", null, 0, 0, null)));
        when(exchangeQueuesPort.listItems(queueId, 0, 200, "tx-", true))
                .thenReturn(ListResultDto.<ExchangeQueueValueDto>of(1, List.of(item("tx-1", ExchangeQueueValueEventType.SUCCESS))))
                .thenReturn(ListResultDto.<ExchangeQueueValueDto>of(2, List.of(
                        item("tx-1", ExchangeQueueValueEventType.SUCCESS), item("tx-2", ExchangeQueueValueEventType.SUCCESS))));

        ScenarioStep step = step(Map.of(
                "queueName", "q",
                "naturalKeys", List.of("tx-"),
                "naturalKeyPrefixMatch", true,
                "expectedStatusCounts", Map.of("SUCCESS", 5)));
        StepRun stepRun = new StepRun(1L, 2L);

        assertThatThrownBy(() -> executor.execute(stepRun, step)).isInstanceOf(StepExecutionException.class);

        // опрос #1 (1 шт.) → #2 (2 шт., размер изменился — не fail-fast, сброс) → #3 (снова 2 шт.,
        // стабильно повторилось — fail-fast); итого 3, не 15 от честного timeout
        verify(exchangeQueuesPort, org.mockito.Mockito.times(3)).listItems(queueId, 0, 200, "tx-", true);
    }

    @Test
    void wrapsOrchestratorApiException() {
        when(exchangeQueuesPort.findByName("q")).thenThrow(new OrchestratorApiException("boom"));

        ScenarioStep step = step(Map.of("queueName", "q"));
        StepRun stepRun = new StepRun(1L, 2L);

        assertThatThrownBy(() -> executor.execute(stepRun, step)).isInstanceOf(StepExecutionException.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void recordsPassedResultWithExpectedActualAndTransactions() {
        UUID queueId = UUID.randomUUID();
        when(exchangeQueuesPort.findByName("q")).thenReturn(Optional.of(new ExchangeQueueDto(queueId, "q", null, 0, 0, null)));
        when(exchangeQueuesPort.listItems(queueId, 0, 200)).thenReturn(ListResultDto.<ExchangeQueueValueDto>of(2, List.of(
                item("k1", ExchangeQueueValueEventType.SUCCESS),
                item("k2", ExchangeQueueValueEventType.ERROR))));
        ScenarioStep step = step(Map.of("queueName", "q", "expectedStatusCounts", Map.of("SUCCESS", 1, "ERROR", 1)));
        StepRun stepRun = new StepRun(1L, 2L);

        executor.execute(stepRun, step);

        assertThat(stepRun.getResult())
                .containsEntry("queueName", "q")
                .containsEntry("passed", true)
                .containsEntry("actualTotal", 2)
                .containsEntry("transactionsTotal", 2);
        assertThat((Map<String, Object>) stepRun.getResult().get("expected")).containsEntry("SUCCESS", 1);
        assertThat((Map<String, Object>) stepRun.getResult().get("actual")).containsEntry("SUCCESS", 1L).containsEntry("ERROR", 1L);
        assertThat((List<Map<String, Object>>) stepRun.getResult().get("transactions"))
                .extracting(t -> t.get("naturalKey"), t -> t.get("status"))
                .containsExactly(org.assertj.core.groups.Tuple.tuple("k1", "SUCCESS"), org.assertj.core.groups.Tuple.tuple("k2", "ERROR"));
    }

    @Test
    void recordsFailedResultWhenTheCheckGivesUp() {
        // the failing snapshot is the one the report must show - the step throws right after it
        UUID queueId = UUID.randomUUID();
        when(exchangeQueuesPort.findByName("q")).thenReturn(Optional.of(new ExchangeQueueDto(queueId, "q", null, 0, 0, null)));
        when(exchangeQueuesPort.listItems(queueId, 0, 200, "k1", false)).thenReturn(
                ListResultDto.<ExchangeQueueValueDto>of(1, List.of(item("k1", ExchangeQueueValueEventType.SUCCESS))));
        ScenarioStep step = step(Map.of(
                "queueName", "q", "naturalKeys", List.of("k1"), "expectedStatusCounts", Map.of("SUCCESS", 5)));
        StepRun stepRun = new StepRun(1L, 2L);

        assertThatThrownBy(() -> executor.execute(stepRun, step)).isInstanceOf(StepExecutionException.class);

        assertThat(stepRun.getResult()).containsEntry("passed", false).containsEntry("actualTotal", 1);
    }

    private ExchangeQueueValueDto item(String naturalKey, ExchangeQueueValueEventType eventType) {
        return new ExchangeQueueValueDto(UUID.randomUUID(), "v", naturalKey, null, null, null, eventType, null, null);
    }

    private ExchangeQueueValueDto deletedItem(String naturalKey, ExchangeQueueValueEventType eventType) {
        return new ExchangeQueueValueDto(UUID.randomUUID(), "v", naturalKey, null, null, LocalDateTime.now(), eventType, null, null);
    }

    private ExchangeQueueValueDto errorItem(String naturalKey, int retray) {
        return new ExchangeQueueValueDto(
                UUID.randomUUID(), "v", naturalKey, null, null, null, ExchangeQueueValueEventType.ERROR, null, retray);
    }

    private ScenarioStep step(Map<String, Object> config) {
        return new ScenarioStep(100L, ScenarioStepType.QUEUE_CHECK, "check", config, 0);
    }
}
