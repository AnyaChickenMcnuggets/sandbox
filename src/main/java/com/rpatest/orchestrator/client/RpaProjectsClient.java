package com.rpatest.orchestrator.client;

import com.rpatest.orchestrator.dto.RpaProjectShortDto;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class RpaProjectsClient implements RpaProjectsPort {

    private final RestClient restClient;

    public RpaProjectsClient(@Qualifier("orchestratorRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    @Retry(name = "orchestrator-read")
    @CircuitBreaker(name = "orchestrator")
    public List<RpaProjectShortDto> list() {
        return OrchestratorClientSupport.execute("list rpa projects", () -> restClient.get()
                .uri("/api/RpaProjects/v3/short")
                .retrieve()
                .body(new ParameterizedTypeReference<List<RpaProjectShortDto>>() {
                }));
    }

    @Override
    public Optional<RpaProjectShortDto> findByName(String name) {
        return list().stream()
                .filter(p -> name.equals(p.name()))
                .max(Comparator.comparing(RpaProjectShortDto::active));
    }
}
