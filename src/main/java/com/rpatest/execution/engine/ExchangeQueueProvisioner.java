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

    public Result ensureExists(String queueName, String description, Integer ttl, Integer maxRetray) {
        return exchangeQueuesPort.findByName(queueName)
                .map(queue -> new Result(queue, false))
                .orElseGet(() -> {
                    exchangeQueuesPort.create(
                            new ExchangeQueueCreateDto(queueName, description, true, ttl, maxRetray, false, true));
                    ExchangeQueueDto queue = exchangeQueuesPort.findByName(queueName)
                            .orElseThrow(() -> new StepExecutionException(
                                    "Очередь '" + queueName + "' не найдена в оркестраторе сразу после создания"));
                    return new Result(queue, true);
                });
    }

    /**
     * {@code created} — реально ли эта очередь была создана только что (а не уже существовала и
     * просто переиспользована). Отдельным шагам (в первую очередь {@code CleanupService}) важно
     * различать эти два случая: удалять на cleanup можно только то, что этот прогон сам создал —
     * переиспользованная чужая/ранее существовавшая очередь под cleanup не должна попадать.
     */
    public record Result(ExchangeQueueDto queue, boolean created) {
    }
}
