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
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Обеспечивает существование очереди (использует существующую или создаёт) и добавляет транзакции. */
@Component
public class QueueStepExecutor implements StepExecutor {

    private static final Logger log = LoggerFactory.getLogger(QueueStepExecutor.class);

    private final ExchangeQueuesPort exchangeQueuesPort;
    private final ExchangeQueueProvisioner queueProvisioner;
    private final StepProgressReporter progressReporter;
    private final ObjectMapper objectMapper;

    public QueueStepExecutor(
            ExchangeQueuesPort exchangeQueuesPort,
            ExchangeQueueProvisioner queueProvisioner,
            StepProgressReporter progressReporter,
            ObjectMapper objectMapper) {
        this.exchangeQueuesPort = exchangeQueuesPort;
        this.queueProvisioner = queueProvisioner;
        this.progressReporter = progressReporter;
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
        List<TransactionTemplate> transactions = config.transactionsOrEmpty();
        log.info("Step '{}' (id={}): queue '{}', transactions to add: {}",
                step.getName(), step.getId(), queueName, transactions.size());
        try {
            progressReporter.report(stepRun, "Looking up/creating queue '" + queueName + "'");
            ExchangeQueueProvisioner.Result provisioned =
                    queueProvisioner.ensureExists(queueName, config.description(), config.ttl(), config.maxRetray());
            ExchangeQueueDto queue = provisioned.queue();
            stepRun.setOrchestratorQueueId(queue.id());
            // На cleanup можно удалять только очередь, которую реально создал этот прогон — если
            // она уже существовала (переиспользована по имени), она не наша, чтобы её удалять.
            stepRun.setOrchestratorQueueOwned(provisioned.created());
            log.info("Step '{}': queue '{}' {}, id={}", step.getName(), queueName,
                    provisioned.created() ? "created" : "already existed (reused)", queue.id());

            int added = 0;
            for (TransactionTemplate transaction : transactions) {
                exchangeQueuesPort.enqueue(
                        queueName,
                        EnqueueExchangeQueueDto.of(transaction.naturalKey(), transaction.value(), transaction.metadata()));
                added++;
                progressReporter.report(stepRun, "Transactions added: " + added + "/" + transactions.size()
                        + " (last naturalKey='" + transaction.naturalKey() + "')");
            }
            progressReporter.report(stepRun, "Queue '" + queueName + "' is ready, transactions added: " + added);
        } catch (OrchestratorApiException e) {
            log.error("Step '{}': orchestrator call failed while working with queue '{}'", step.getName(), queueName, e);
            throw new StepExecutionException("Failed to execute queue step '" + step.getName() + "'", e);
        }
    }
}
