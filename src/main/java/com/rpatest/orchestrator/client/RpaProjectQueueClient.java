package com.rpatest.orchestrator.client;

import com.rpatest.orchestrator.dto.QueueItemProjectDto;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class RpaProjectQueueClient implements RpaProjectQueuePort {

    private final RestClient restClient;

    public RpaProjectQueueClient(@Qualifier("orchestratorRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    @Retry(name = "orchestrator-read")
    @CircuitBreaker(name = "orchestrator")
    public List<QueueItemProjectDto> findByAssignment(int assignmentId) {
        List<QueueItemProjectDto> items = OrchestratorClientSupport.execute(
                "find project queue entries for assignment " + assignmentId, () -> restClient.get()
                        .uri(uriBuilder -> uriBuilder.path("/api/RpaProjectQueue")
                                .queryParam("AssignmentId", assignmentId)
                                .build())
                        .retrieve()
                        .body(new ParameterizedTypeReference<List<QueueItemProjectDto>>() {
                        }));
        return items != null ? items : List.of();
    }
}
