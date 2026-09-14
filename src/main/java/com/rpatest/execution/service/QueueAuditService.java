package com.rpatest.execution.service;

import com.rpatest.common.exception.InvalidRequestException;
import com.rpatest.common.exception.NotFoundException;
import com.rpatest.execution.domain.QueueItemResult;
import com.rpatest.execution.repository.QueueItemResultRepository;
import com.rpatest.execution.repository.StepRunRepository;
import com.rpatest.execution.web.QueueItemResponse;
import com.rpatest.orchestrator.client.ExchangeQueuesPort;
import com.rpatest.orchestrator.dto.ExchangeQueueValueDto;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Читает элементы очереди из оркестратора для аудита прогона и сохраняет снапшот в БД. */
@Service
public class QueueAuditService {

    private final StepRunRepository stepRunRepository;
    private final QueueItemResultRepository queueItemResultRepository;
    private final ExchangeQueuesPort exchangeQueuesPort;

    public QueueAuditService(
            StepRunRepository stepRunRepository,
            QueueItemResultRepository queueItemResultRepository,
            ExchangeQueuesPort exchangeQueuesPort) {
        this.stepRunRepository = stepRunRepository;
        this.queueItemResultRepository = queueItemResultRepository;
        this.exchangeQueuesPort = exchangeQueuesPort;
    }

    @Transactional
    public List<QueueItemResponse> auditQueueItems(Long runId, Long stepId, int pageNumber, int pageSize) {
        return auditQueueItems(runId, stepId, pageNumber, pageSize, null, false);
    }

    /**
     * @param naturalKey если задан — поиск идёт по фильтру оркестратора ({@code NaturalKey}/
     *                   {@code NaturalKeyPart} у {@code GET .../v2/{id}/Items}), а не постраничным
     *                   перебором всей очереди: в очереди могут быть тысячи транзакций, из которых
     *                   интересны единицы известных ключей.
     * @param naturalKeyPart {@code false} — точное совпадение ключа, {@code true} — по префиксу
     */
    @Transactional
    public List<QueueItemResponse> auditQueueItems(
            Long runId, Long stepId, int pageNumber, int pageSize, String naturalKey, boolean naturalKeyPart) {
        var stepRun = stepRunRepository.findByScenarioRunIdAndStepId(runId, stepId)
                .orElseThrow(() -> new NotFoundException("StepRun не найден для run=" + runId + ", step=" + stepId));
        if (stepRun.getOrchestratorQueueId() == null) {
            throw new InvalidRequestException("Шаг " + stepId + " не создавал очередь в оркестраторе");
        }

        List<ExchangeQueueValueDto> items = (naturalKey == null || naturalKey.isBlank()
                ? exchangeQueuesPort.listItems(stepRun.getOrchestratorQueueId(), pageNumber, pageSize)
                : exchangeQueuesPort.listItems(
                        stepRun.getOrchestratorQueueId(), pageNumber, pageSize, naturalKey, naturalKeyPart))
                .result();
        if (items == null) {
            items = List.of();
        }

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
}
