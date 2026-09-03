package com.rpatest.execution.service;

import com.rpatest.common.exception.NotFoundException;
import com.rpatest.execution.domain.StepRun;
import com.rpatest.execution.repository.ScenarioRunRepository;
import com.rpatest.execution.repository.StepRunRepository;
import com.rpatest.orchestrator.client.AssignmentsPort;
import com.rpatest.orchestrator.client.ExchangeQueuesPort;
import com.rpatest.orchestrator.exception.OrchestratorApiException;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Удаляет из оркестратора Assignments/ExchangeQueues, созданные последним прогоном сценария. */
@Service
public class CleanupService {

    private static final Logger log = LoggerFactory.getLogger(CleanupService.class);

    private final ScenarioRunRepository runRepository;
    private final StepRunRepository stepRunRepository;
    private final AssignmentsPort assignmentsPort;
    private final ExchangeQueuesPort exchangeQueuesPort;

    public CleanupService(
            ScenarioRunRepository runRepository,
            StepRunRepository stepRunRepository,
            AssignmentsPort assignmentsPort,
            ExchangeQueuesPort exchangeQueuesPort) {
        this.runRepository = runRepository;
        this.stepRunRepository = stepRunRepository;
        this.assignmentsPort = assignmentsPort;
        this.exchangeQueuesPort = exchangeQueuesPort;
    }

    @Transactional(readOnly = true)
    public List<String> cleanupLastRun(Long scenarioId) {
        var run = runRepository.findFirstByScenarioIdOrderByIdDesc(scenarioId)
                .orElseThrow(() -> new NotFoundException("Для сценария " + scenarioId + " ещё не было прогонов"));

        List<String> failures = new ArrayList<>();
        for (StepRun step : stepRunRepository.findByScenarioRunId(run.getId())) {
            if (step.getOrchestratorAssignmentId() != null) {
                deleteSafely(failures, "assignment " + step.getOrchestratorAssignmentId(),
                        () -> assignmentsPort.delete(step.getOrchestratorAssignmentId()));
            }
            if (step.getOrchestratorQueueId() != null) {
                deleteSafely(failures, "queue " + step.getOrchestratorQueueId(),
                        () -> exchangeQueuesPort.delete(step.getOrchestratorQueueId()));
            }
        }
        return failures;
    }

    private void deleteSafely(List<String> failures, String description, Runnable action) {
        try {
            action.run();
        } catch (OrchestratorApiException e) {
            log.warn("Не удалось удалить {} при cleanup", description, e);
            failures.add(description + ": " + e.getMessage());
        }
    }
}
