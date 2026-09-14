package com.rpatest.orchestrator.client;

import com.rpatest.orchestrator.dto.ListResultDto;
import com.rpatest.orchestrator.dto.RobotDto;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class RobotsClient implements RobotsPort {

    private final RestClient restClient;

    public RobotsClient(@Qualifier("orchestratorRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    @Retry(name = "orchestrator-read")
    @CircuitBreaker(name = "orchestrator")
    public List<RobotDto> list() {
        // GET /api/Robots/v2 без пагинации — подтверждённый рабочий вызов (см. OrcService.java,
        // getRpaRobots): реальные стенды не настолько велики, чтобы список роботов не помещался
        // в страницу по умолчанию оркестратора.
        ListResultDto<RobotDto> page = OrchestratorClientSupport.execute("list robots", () -> restClient.get()
                .uri("/api/Robots/v2")
                .retrieve()
                .body(new ParameterizedTypeReference<ListResultDto<RobotDto>>() {
                }));
        return page.result() == null ? List.of() : page.result();
    }
}
