package com.rpatest.execution.engine;

import com.rpatest.orchestrator.client.ExchangeQueuesPort;
import com.rpatest.orchestrator.dto.ExchangeQueueCreateDto;
import com.rpatest.orchestrator.dto.ExchangeQueueDto;
import org.springframework.stereotype.Component;

/**
 * Создание очереди — идемпотентная операция "используй существующую, иначе создай": очередь в
 * оркестраторе имеет фиксированное имя (совпадающее с тем, что зашито в RPA-проекте), поэтому
 * при повторном запуске сценария (без cleanup) её пересоздание с тем же именем не требуется и
 * рискует упасть на конфликте имени.
 */
@Component
public class ExchangeQueueProvisioner {

    private final ExchangeQueuesPort exchangeQueuesPort;

    public ExchangeQueueProvisioner(ExchangeQueuesPort exchangeQueuesPort) {
        this.exchangeQueuesPort = exchangeQueuesPort;
    }

    public ExchangeQueueDto ensureExists(String queueName, String description, Integer ttl, Integer maxRetray) {
        return exchangeQueuesPort.findByName(queueName).orElseGet(() -> {
            exchangeQueuesPort.create(
                    new ExchangeQueueCreateDto(queueName, description, true, ttl, maxRetray, false, true));
            return exchangeQueuesPort.findByName(queueName)
                    .orElseThrow(() -> new StepExecutionException(
                            "Очередь '" + queueName + "' не найдена в оркестраторе сразу после создания"));
        });
    }
}
