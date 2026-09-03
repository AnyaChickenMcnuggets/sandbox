package com.rpatest.orchestrator.client;

import com.rpatest.orchestrator.dto.EnqueueExchangeQueueDto;
import com.rpatest.orchestrator.dto.ExchangeQueueCreateDto;
import com.rpatest.orchestrator.dto.ExchangeQueueDto;
import com.rpatest.orchestrator.dto.ExchangeQueueValueDto;
import com.rpatest.orchestrator.dto.PageDto;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class ExchangeQueuesClient implements ExchangeQueuesPort {

    private final RestClient restClient;

    public ExchangeQueuesClient(@Qualifier("orchestratorRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    @Retry(name = "orchestrator-write")
    @CircuitBreaker(name = "orchestrator")
    public void create(ExchangeQueueCreateDto request) {
        OrchestratorClientSupport.execute("create exchange queue " + request.name(), () -> restClient.post()
                .uri("/api/ExchangeQueues")
                .body(request)
                .retrieve()
                .toBodilessEntity());
    }

    @Override
    @Retry(name = "orchestrator-read")
    @CircuitBreaker(name = "orchestrator")
    public List<ExchangeQueueDto> list() {
        return OrchestratorClientSupport.execute("list exchange queues", () -> restClient.get()
                .uri("/api/ExchangeQueues")
                .retrieve()
                .body(new ParameterizedTypeReference<List<ExchangeQueueDto>>() {
                }));
    }

    @Override
    public Optional<ExchangeQueueDto> findByName(String name) {
        return list().stream().filter(q -> name.equals(q.name())).findFirst();
    }

    @Override
    @Retry(name = "orchestrator-write")
    @CircuitBreaker(name = "orchestrator")
    public void enqueue(String queueName, EnqueueExchangeQueueDto item) {
        OrchestratorClientSupport.execute("enqueue item into " + queueName, () -> restClient.put()
                .uri("/api/ExchangeQueues/v2/enqueue/{queueName}", queueName)
                .body(item)
                .retrieve()
                .toBodilessEntity());
    }

    @Override
    @Retry(name = "orchestrator-read")
    @CircuitBreaker(name = "orchestrator")
    public PageDto<ExchangeQueueValueDto> listItems(UUID queueId, int pageNumber, int pageSize) {
        return OrchestratorClientSupport.execute("list items of queue " + queueId, () -> restClient.get()
                .uri(uriBuilder -> uriBuilder.path("/api/ExchangeQueues/{id}/Items")
                        .queryParam("pageNumber", pageNumber)
                        .queryParam("pageSize", pageSize)
                        .build(queueId))
                .retrieve()
                .body(new ParameterizedTypeReference<PageDto<ExchangeQueueValueDto>>() {
                }));
    }

    @Override
    @Retry(name = "orchestrator-write")
    @CircuitBreaker(name = "orchestrator")
    public void delete(UUID queueId) {
        OrchestratorClientSupport.execute("delete exchange queue " + queueId, () -> restClient.delete()
                .uri("/api/ExchangeQueues/{id}", queueId)
                .retrieve()
                .toBodilessEntity());
    }
}
