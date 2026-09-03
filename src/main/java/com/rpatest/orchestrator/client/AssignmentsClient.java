package com.rpatest.orchestrator.client;

import com.rpatest.orchestrator.dto.AssignmentCreateDto;
import com.rpatest.orchestrator.dto.AssignmentDto;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class AssignmentsClient implements AssignmentsPort {

    private final RestClient restClient;

    public AssignmentsClient(@Qualifier("orchestratorRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    @Retry(name = "orchestrator-write")
    @CircuitBreaker(name = "orchestrator")
    public AssignmentDto create(AssignmentCreateDto request) {
        return OrchestratorClientSupport.execute("create assignment", () -> restClient.post()
                .uri("/api/Assignments/v2")
                .body(request)
                .retrieve()
                .body(AssignmentDto.class));
    }

    @Override
    @Retry(name = "orchestrator-read")
    @CircuitBreaker(name = "orchestrator")
    public AssignmentDto get(int assignmentId) {
        return OrchestratorClientSupport.execute("get assignment " + assignmentId, () -> restClient.get()
                .uri("/api/Assignments/v2/{id}", assignmentId)
                .retrieve()
                .body(AssignmentDto.class));
    }

    @Override
    @Retry(name = "orchestrator-write")
    @CircuitBreaker(name = "orchestrator")
    public void start(int assignmentId) {
        OrchestratorClientSupport.execute("start assignment " + assignmentId, () -> restClient.put()
                .uri("/api/Assignments/{id}/Start", assignmentId)
                .retrieve()
                .toBodilessEntity());
    }

    @Override
    @Retry(name = "orchestrator-write")
    @CircuitBreaker(name = "orchestrator")
    public void stop(int assignmentId) {
        OrchestratorClientSupport.execute("stop assignment " + assignmentId, () -> restClient.put()
                .uri("/api/Assignments/{id}/Stop", assignmentId)
                .retrieve()
                .toBodilessEntity());
    }

    @Override
    @Retry(name = "orchestrator-write")
    @CircuitBreaker(name = "orchestrator")
    public void delete(int assignmentId) {
        OrchestratorClientSupport.execute("delete assignment " + assignmentId, () -> restClient.delete()
                .uri("/api/Assignments/{id}", assignmentId)
                .retrieve()
                .toBodilessEntity());
    }
}
