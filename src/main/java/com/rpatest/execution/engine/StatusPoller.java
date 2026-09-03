package com.rpatest.execution.engine;

import com.rpatest.config.OrchestratorProperties;
import com.rpatest.orchestrator.client.AssignmentsPort;
import com.rpatest.orchestrator.dto.AssignmentDto;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Component;

/** Периодически опрашивает статус Assignment до терминального состояния или таймаута. */
@Component
public class StatusPoller {

    private final AssignmentsPort assignmentsPort;
    private final OrchestratorProperties properties;

    public StatusPoller(AssignmentsPort assignmentsPort, OrchestratorProperties properties) {
        this.assignmentsPort = assignmentsPort;
        this.properties = properties;
    }

    public AssignmentDto pollUntilTerminal(int assignmentId) {
        Duration interval = properties.getPolling().getInterval();
        Instant deadline = Instant.now().plus(properties.getPolling().getTimeout());
        while (true) {
            AssignmentDto current = assignmentsPort.get(assignmentId);
            if (current.status().isTerminal()) {
                return current;
            }
            if (Instant.now().isAfter(deadline)) {
                throw new StepExecutionException(
                        "Таймаут ожидания завершения Assignment id=" + assignmentId);
            }
            sleep(interval);
        }
    }

    private void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StepExecutionException("Ожидание статуса Assignment было прервано", e);
        }
    }
}
