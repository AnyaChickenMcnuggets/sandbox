package com.rpatest.execution.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rpatest.execution.domain.StepRun;
import com.rpatest.execution.engine.config.QueueStepConfig;
import com.rpatest.execution.engine.config.TransactionTemplate;
import com.rpatest.orchestrator.client.ExchangeQueuesPort;
import com.rpatest.orchestrator.dto.EnqueueExchangeQueueDto;
import com.rpatest.orchestrator.dto.ExchangeQueueCreateDto;
import com.rpatest.orchestrator.dto.ExchangeQueueDto;
import com.rpatest.orchestrator.exception.OrchestratorApiException;
import com.rpatest.scenario.domain.ScenarioStep;
import com.rpatest.scenario.domain.ScenarioStepType;
import org.springframework.stereotype.Component;

@Component
public class QueueStepExecutor implements StepExecutor {

    private final ExchangeQueuesPort exchangeQueuesPort;
    private final ObjectMapper objectMapper;

    public QueueStepExecutor(ExchangeQueuesPort exchangeQueuesPort, ObjectMapper objectMapper) {
        this.exchangeQueuesPort = exchangeQueuesPort;
        this.objectMapper = objectMapper;
    }

    @Override
    public ScenarioStepType supports() {
        return ScenarioStepType.QUEUE;
    }

    @Override
    public void execute(StepRun stepRun, ScenarioStep step) {
        QueueStepConfig config = objectMapper.convertValue(step.getConfig(), QueueStepConfig.class);
        try {
            exchangeQueuesPort.create(new ExchangeQueueCreateDto(
                    config.name(), config.description(), true, config.ttl(), config.maxRetray(), false, true));

            ExchangeQueueDto queue = exchangeQueuesPort.findByName(config.name())
                    .orElseThrow(() -> new StepExecutionException(
                            "Очередь '" + config.name() + "' не найдена в оркестраторе сразу после создания"));
            stepRun.setOrchestratorQueueId(queue.id());

            for (TransactionTemplate transaction : config.transactionsOrEmpty()) {
                exchangeQueuesPort.enqueue(
                        config.name(),
                        EnqueueExchangeQueueDto.of(transaction.naturalKey(), transaction.value(), transaction.metadata()));
            }
        } catch (OrchestratorApiException e) {
            throw new StepExecutionException("Не удалось выполнить шаг очереди '" + step.getName() + "'", e);
        }
    }
}
