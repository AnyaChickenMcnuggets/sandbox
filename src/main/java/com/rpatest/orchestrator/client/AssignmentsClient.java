package com.rpatest.orchestrator.client;

import com.rpatest.orchestrator.dto.AssignmentCreateDto;
import com.rpatest.orchestrator.dto.AssignmentDto;
import com.rpatest.orchestrator.exception.OrchestratorApiException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
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
        return OrchestratorClientSupport.execute("create assignment " + request.name(), () -> {
            AssignmentDto created = restClient.post()
                    .uri("/api/Assignments/v2")
                    .body(request)
                    .retrieve()
                    .body(AssignmentDto.class);
            // POST /api/Assignments/v2 не всегда возвращает тело созданного задания —
            // подтверждено на реальном стенде (см. OrcService.postRpaTask). В этом случае
            // ищем только что созданное задание по имени через список.
            return created != null ? created : findByName(request.name());
        });
    }

    private AssignmentDto findByName(String name) {
        return list().stream()
                .filter(a -> name.equals(a.name()))
                .reduce((first, second) -> second)
                .orElseThrow(() -> new OrchestratorApiException(
                        "Задание '" + name + "' не найдено в оркестраторе после создания (пустой ответ POST)"));
    }

    @Override
    @Retry(name = "orchestrator-read")
    @CircuitBreaker(name = "orchestrator")
    public List<AssignmentDto> list() {
        return OrchestratorClientSupport.execute("list assignments", () -> restClient.get()
                .uri("/api/Assignments/v2")
                .retrieve()
                .body(new ParameterizedTypeReference<List<AssignmentDto>>() {
                }));
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
