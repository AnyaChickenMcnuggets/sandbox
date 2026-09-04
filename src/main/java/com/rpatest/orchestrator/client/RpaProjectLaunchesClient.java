package com.rpatest.orchestrator.client;

import com.rpatest.orchestrator.dto.ListResultDto;
import com.rpatest.orchestrator.dto.RpaProjectLaunchDto;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class RpaProjectLaunchesClient implements RpaProjectLaunchesPort {

    private static final int PAGE_SIZE = 50;

    private final RestClient restClient;

    public RpaProjectLaunchesClient(@Qualifier("orchestratorRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    @Retry(name = "orchestrator-read")
    @CircuitBreaker(name = "orchestrator")
    public List<RpaProjectLaunchDto> getByAssignment(int assignmentId) {
        ListResultDto<RpaProjectLaunchDto> page = OrchestratorClientSupport.execute(
                "get launches for assignment " + assignmentId, () -> restClient.get()
                        .uri(uriBuilder -> uriBuilder.path("/api/RpaProjectLaunches/assignment/{assignmentId}")
                                .queryParam("pageNumber", 0)
                                .queryParam("pageSize", PAGE_SIZE)
                                .build(assignmentId))
                        .retrieve()
                        .body(new ParameterizedTypeReference<ListResultDto<RpaProjectLaunchDto>>() {
                        }));
        return page != null && page.result() != null ? page.result() : List.of();
    }
}
