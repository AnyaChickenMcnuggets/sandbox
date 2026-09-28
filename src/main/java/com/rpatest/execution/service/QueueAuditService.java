package com.rpatest.execution.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rpatest.common.exception.InvalidRequestException;
import com.rpatest.common.exception.NotFoundException;
import com.rpatest.execution.domain.QueueItemResult;
import com.rpatest.execution.domain.StepRun;
import com.rpatest.execution.engine.QueueItemFinder;
import com.rpatest.execution.engine.config.QueueCheckStepConfig;
import com.rpatest.execution.engine.config.QueueStepConfig;
import com.rpatest.execution.engine.config.TransactionTemplate;
import com.rpatest.execution.repository.QueueItemResultRepository;
import com.rpatest.execution.repository.StepRunRepository;
import com.rpatest.execution.web.QueueItemResponse;
import com.rpatest.orchestrator.client.ExchangeQueuesPort;
import com.rpatest.orchestrator.dto.ExchangeQueueValueDto;
import com.rpatest.scenario.domain.ScenarioStep;
import com.rpatest.scenario.domain.ScenarioStepType;
import com.rpatest.scenario.repository.ScenarioStepRepository;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Читает элементы очереди из оркестратора для аудита прогона и сохраняет снапшот в БД. */
@Service
public class QueueAuditService {

    private final StepRunRepository stepRunRepository;
    private final QueueItemResultRepository queueItemResultRepository;
    private final ExchangeQueuesPort exchangeQueuesPort;
    private final ScenarioStepRepository scenarioStepRepository;
    private final QueueItemFinder queueItemFinder;
    private final ObjectMapper objectMapper;

    public QueueAuditService(
            StepRunRepository stepRunRepository,
            QueueItemResultRepository queueItemResultRepository,
            ExchangeQueuesPort exchangeQueuesPort,
            ScenarioStepRepository scenarioStepRepository,
            QueueItemFinder queueItemFinder,
            ObjectMapper objectMapper) {
        this.stepRunRepository = stepRunRepository;
        this.queueItemResultRepository = queueItemResultRepository;
        this.exchangeQueuesPort = exchangeQueuesPort;
        this.scenarioStepRepository = scenarioStepRepository;
        this.queueItemFinder = queueItemFinder;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public List<QueueItemResponse> auditQueueItems(Long runId, Long stepId, int pageNumber, int pageSize) {
        return auditQueueItems(runId, stepId, pageNumber, pageSize, null, false);
    }

    /**
     * Без явного {@code naturalKey} аудит по умолчанию показывает не всю очередь, а только то, что
     * относится к самому шагу: для {@code QUEUE} — natural key'и его собственных отправленных
     * транзакций, для {@code QUEUE_CHECK} — его {@code naturalKeys}/{@code naturalKeyPrefixMatch}
     * (см. AGENTS.md, "Списки элементов очереди"). Явный {@code naturalKey} всегда перекрывает это
     * автоматическое сужение. Если ни явного фильтра, ни известных шагу ключей нет (например, шаг
     * без наперёд заданных транзакций/ключей, либо {@code scenario_step} уже удалён — см.
     * денормализацию в AGENTS.md) — единственный оставшийся осмысленный режим — постраничный обзор
     * всей очереди по {@code pageNumber}/{@code pageSize}, как и раньше.
     *
     * @param naturalKey если задан — поиск идёт по этому ключу вместо автоматического сужения
     * @param naturalKeyPart {@code false} — точное совпадение ключа, {@code true} — по префиксу
     */
    @Transactional
    public List<QueueItemResponse> auditQueueItems(
            Long runId, Long stepId, int pageNumber, int pageSize, String naturalKey, boolean naturalKeyPart) {
        StepRun stepRun = stepRunRepository.findByScenarioRunIdAndStepId(runId, stepId)
                .orElseThrow(() -> new NotFoundException("StepRun не найден для run=" + runId + ", step=" + stepId));
        if (stepRun.getOrchestratorQueueId() == null) {
            throw new InvalidRequestException("Шаг " + stepId + " не создавал очередь в оркестраторе");
        }

        NaturalKeyFilter filter = naturalKey != null && !naturalKey.isBlank()
                ? new NaturalKeyFilter(Set.of(naturalKey), naturalKeyPart)
                : deriveFilter(stepRun.getStepId());

        List<ExchangeQueueValueDto> items = filter.naturalKeys().isEmpty()
                // Ни явного, ни выводимого из шага фильтра — единственный осмысленный режим
                // здесь: постраничный обзор конкретной страницы очереди, как просил вызывающий.
                ? results(exchangeQueuesPort.listItems(stepRun.getOrchestratorQueueId(), pageNumber, pageSize).result())
                : queueItemFinder.find(stepRun.getOrchestratorQueueId(), filter.naturalKeys(), filter.prefixMatch());

        for (ExchangeQueueValueDto item : items) {
            queueItemResultRepository.save(new QueueItemResult(
                    stepRun.getId(), item.id(), item.naturalKey(), item.derivedStatus().name(),
                    Map.of("value", String.valueOf(item.value()))));
        }

        return items.stream()
                .map(i -> new QueueItemResponse(
                        i.id(),
                        i.naturalKey(),
                        i.value(),
                        i.createdAt(),
                        i.lastEventType() == null ? null : i.lastEventType().name(),
                        i.lastEventText()))
                .toList();
    }

    private List<ExchangeQueueValueDto> results(List<ExchangeQueueValueDto> items) {
        return items == null ? List.of() : items;
    }

    private NaturalKeyFilter deriveFilter(Long stepId) {
        if (stepId == null) {
            return NaturalKeyFilter.unfiltered();
        }
        return scenarioStepRepository.findById(stepId).map(this::deriveFilter).orElseGet(NaturalKeyFilter::unfiltered);
    }

    private NaturalKeyFilter deriveFilter(ScenarioStep step) {
        if (step.getType() == ScenarioStepType.QUEUE) {
            QueueStepConfig config = objectMapper.convertValue(step.getConfig(), QueueStepConfig.class);
            Set<String> naturalKeys = config.transactionsOrEmpty().stream()
                    .map(TransactionTemplate::naturalKey)
                    .collect(Collectors.toSet());
            return new NaturalKeyFilter(naturalKeys, false);
        }
        if (step.getType() == ScenarioStepType.QUEUE_CHECK) {
            QueueCheckStepConfig config = objectMapper.convertValue(step.getConfig(), QueueCheckStepConfig.class);
            return new NaturalKeyFilter(Set.copyOf(config.naturalKeysOrEmpty()), config.isNaturalKeyPrefixMatch());
        }
        return NaturalKeyFilter.unfiltered();
    }

    private record NaturalKeyFilter(Set<String> naturalKeys, boolean prefixMatch) {
        static NaturalKeyFilter unfiltered() {
            return new NaturalKeyFilter(Set.of(), false);
        }
    }
}
