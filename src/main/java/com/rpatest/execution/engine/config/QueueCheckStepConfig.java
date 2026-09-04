package com.rpatest.execution.engine.config;

import java.util.List;
import java.util.Map;

/**
 * Форма JSONB-конфига ScenarioStep(type=QUEUE_CHECK), сохраняемая через scenario API.
 * Проверяет транзакции уже существующей очереди (созданной более ранним QUEUE-шагом того же
 * сценария) против ожиданий автора сценария — единственный надёжный способ узнать, что задание
 * реально обработало данные, а не просто было поставлено оркестратором в очередь на выполнение.
 *
 * @param queueName имя проверяемой очереди (как в config.name соответствующего QUEUE-шага)
 * @param naturalKeys если задано — проверяются только элементы, чей natural key совпадает с одним
 *                    из этих значений ({@code naturalKeyPrefixMatch=false}, по умолчанию — типично
 *                    для входной очереди, где natural key известен точно, мы сами его туда клали)
 *                    либо начинается с одного из них ({@code naturalKeyPrefixMatch=true} — типично
 *                    для выходной очереди: сквозной ключ сохраняется от входа к выходу, но на
 *                    выходе к нему может дописываться суффикс для трассировки при разветвлении
 *                    одной входной транзакции на несколько выходных); если пусто — все элементы
 *                    очереди на момент проверки
 * @param naturalKeyPrefixMatch см. {@link #naturalKeys}; по умолчанию {@code false} (точное совпадение)
 * @param expectedStatusCounts точные ожидаемые количества по статусу — ключи "SUCCESS",
 *                    "ERROR", "BUSINESS_ERROR", "NEW", "IN_PROGRESS" (см. {@link
 *                    com.rpatest.orchestrator.dto.QueueItemDerivedStatus}); проверяются только
 *                    перечисленные статусы, остальные не ограничиваются
 * @param minTotalCount минимальное количество подходящих (под naturalKeys) элементов — для
 *                    случаев, когда точное количество результатов заранее не известно (N входных
 *                    транзакций могут превратиться в M выходных с тем же базовым natural key)
 * @param timeoutSeconds таймаут ожидания (null — берётся из orchestrator.queue-check-polling)
 * @param pollIntervalSeconds интервал опроса (null — берётся из orchestrator.queue-check-polling)
 */
public record QueueCheckStepConfig(
        String queueName,
        List<String> naturalKeys,
        Boolean naturalKeyPrefixMatch,
        Map<String, Integer> expectedStatusCounts,
        Integer minTotalCount,
        Long timeoutSeconds,
        Long pollIntervalSeconds) {

    public List<String> naturalKeysOrEmpty() {
        return naturalKeys == null ? List.of() : naturalKeys;
    }

    public boolean isNaturalKeyPrefixMatch() {
        return Boolean.TRUE.equals(naturalKeyPrefixMatch);
    }

    public Map<String, Integer> expectedStatusCountsOrEmpty() {
        return expectedStatusCounts == null ? Map.of() : expectedStatusCounts;
    }
}
