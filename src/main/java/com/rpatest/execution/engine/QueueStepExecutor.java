package com.rpatest.execution.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rpatest.execution.domain.StepRun;
import com.rpatest.execution.engine.config.QueueStepConfig;
import com.rpatest.execution.engine.config.TransactionTemplate;
import com.rpatest.orchestrator.client.ExchangeQueuesPort;
import com.rpatest.orchestrator.dto.EnqueueExchangeQueueDto;
import com.rpatest.orchestrator.dto.ExchangeQueueDto;
import com.rpatest.orchestrator.exception.OrchestratorApiException;
import com.rpatest.orchestrator.util.OrchestratorNames;
import com.rpatest.scenario.domain.ScenarioStep;
import com.rpatest.scenario.domain.ScenarioStepType;
import org.springframework.stereotype.Component;

/** Обеспечивает существование очереди (использует существующую или создаёт) и добавляет транзакции. */
@Component
public class QueueStepExecutor implements StepExecutor {

    private final ExchangeQueuesPort exchangeQueuesPort;
    private final ExchangeQueueProvisioner queueProvisioner;
    private final ObjectMapper objectMapper;

    public QueueStepExecutor(
            ExchangeQueuesPort exchangeQueuesPort, ExchangeQueueProvisioner queueProvisioner, ObjectMapper objectMapper) {
        this.exchangeQueuesPort = exchangeQueuesPort;
        this.queueProvisioner = queueProvisioner;
        this.objectMapper = objectMapper;
    }

    @Override
    public ScenarioStepType supports() {
        return ScenarioStepType.QUEUE;
    }

    @Override
    public void execute(StepRun stepRun, ScenarioStep step) {
        QueueStepConfig config = objectMapper.convertValue(step.getConfig(), QueueStepConfig.class);
        // Оркестратор принимает в имени очереди только латиницу/цифры/подчёркивание.
        String queueName = OrchestratorNames.sanitize(config.name());
        try {
            ExchangeQueueDto queue =
                    queueProvisioner.ensureExists(queueName, config.description(), config.ttl(), config.maxRetray());
            stepRun.setOrchestratorQueueId(queue.id());

            for (TransactionTemplate transaction : config.transactionsOrEmpty()) {
                exchangeQueuesPort.enqueue(
                        queueName,
                        EnqueueExchangeQueueDto.of(transaction.naturalKey(), transaction.value(), transaction.metadata()));
            }
        } catch (OrchestratorApiException e) {
            throw new StepExecutionException("Не удалось выполнить шаг очереди '" + step.getName() + "'", e);
        }
    }
}
