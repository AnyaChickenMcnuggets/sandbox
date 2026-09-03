package com.rpatest.orchestrator.client;

import com.rpatest.orchestrator.dto.RpaProjectVariableDto;
import com.rpatest.orchestrator.dto.RpaProjectVariableEditByIdDto;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class RpaProjectVariablesClient implements RpaProjectVariablesPort {

    private final RestClient restClient;

    public RpaProjectVariablesClient(@Qualifier("orchestratorRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    @Retry(name = "orchestrator-read")
    @CircuitBreaker(name = "orchestrator")
    public List<RpaProjectVariableDto> get(int assignmentId) {
        return OrchestratorClientSupport.execute("get assignment variables " + assignmentId, () -> restClient.get()
                .uri("/api/RpaProjectVariables/Assignment/{assignmentId}", assignmentId)
                .retrieve()
                .body(new ParameterizedTypeReference<List<RpaProjectVariableDto>>() {
                }));
    }

    @Override
    @Retry(name = "orchestrator-write")
    @CircuitBreaker(name = "orchestrator")
    public void update(int assignmentId, List<RpaProjectVariableEditByIdDto> values) {
        OrchestratorClientSupport.execute("update assignment variables " + assignmentId, () -> restClient.put()
                .uri("/api/RpaProjectVariables/Assignment/{assignmentId}", assignmentId)
                .body(values)
                .retrieve()
                .toBodilessEntity());
    }
}
