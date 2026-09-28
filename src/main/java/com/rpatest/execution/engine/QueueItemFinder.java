package com.rpatest.execution.engine;

import com.rpatest.orchestrator.client.ExchangeQueuesPort;
import com.rpatest.orchestrator.dto.ExchangeQueueValueDto;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Единственное место, откуда очередь оркестратора читается для поиска/подсчёта элементов — см.
 * AGENTS.md, "Списки элементов очереди — только через фильтр оркестратора". Пустой {@code
 * naturalKeys} — полный постраничный перебор (нужен реальный подсчёт/весь список); непустой —
 * отдельный отфильтрованный запрос ({@code NaturalKey}/{@code NaturalKeyPart}) на каждый ключ,
 * никогда не перебор всей (потенциально многотысячной) очереди ради нескольких известных ключей.
 */
@Component
public class QueueItemFinder {

    private static final int PAGE_SIZE = 200;
    private static final int MAX_PAGES = 50;

    private final ExchangeQueuesPort exchangeQueuesPort;

    public QueueItemFinder(ExchangeQueuesPort exchangeQueuesPort) {
        this.exchangeQueuesPort = exchangeQueuesPort;
    }

    public List<ExchangeQueueValueDto> find(UUID queueId, Set<String> naturalKeys, boolean naturalKeyPart) {
        List<ExchangeQueueValueDto> all = naturalKeys.isEmpty()
                ? fetchAllPages(queueId, null, false)
                : fetchByNaturalKeys(queueId, naturalKeys, naturalKeyPart);
        // Удалённые транзакции (deletedAt != null) не должны фигурировать в результате — иначе
        // удаление элемента из очереди (вручную или самим оркестратором) искажает и общее число
        // элементов, и то, что видит вызывающий (проверка ожиданий, ручной аудит).
        all = all.stream().filter(i -> i.deletedAt() == null).toList();
        if (naturalKeys.isEmpty()) {
            return all;
        }
        // Сервер уже отфильтровал по каждому ключу (NaturalKey/NaturalKeyPart), но точную
        // семантику "начинается с" для prefix-режима перепроверяем сами — не задокументировано,
        // что NaturalKeyPart на стороне оркестратора означает именно префикс, а не вхождение где
        // угодно в строке, а наш контракт — строго префикс.
        if (naturalKeyPart) {
            return all.stream()
                    .filter(i -> i.naturalKey() != null
                            && naturalKeys.stream().anyMatch(prefix -> i.naturalKey().startsWith(prefix)))
                    .toList();
        }
        return all.stream().filter(i -> i.naturalKey() != null && naturalKeys.contains(i.naturalKey())).toList();
    }

    private List<ExchangeQueueValueDto> fetchByNaturalKeys(UUID queueId, Set<String> naturalKeys, boolean naturalKeyPart) {
        List<ExchangeQueueValueDto> result = new ArrayList<>();
        for (String naturalKey : naturalKeys) {
            result.addAll(fetchAllPages(queueId, naturalKey, naturalKeyPart));
        }
        return result;
    }

    private List<ExchangeQueueValueDto> fetchAllPages(UUID queueId, String naturalKey, boolean naturalKeyPart) {
        List<ExchangeQueueValueDto> all = new ArrayList<>();
        for (int page = 0; page < MAX_PAGES; page++) {
            List<ExchangeQueueValueDto> items = naturalKey == null
                    ? exchangeQueuesPort.listItems(queueId, page, PAGE_SIZE).result()
                    : exchangeQueuesPort.listItems(queueId, page, PAGE_SIZE, naturalKey, naturalKeyPart).result();
            if (items == null || items.isEmpty()) {
                break;
            }
            all.addAll(items);
            if (items.size() < PAGE_SIZE) {
                break;
            }
        }
        return all;
    }
}
