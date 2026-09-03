package com.rpatest.execution.repository;

import com.rpatest.execution.domain.StepRun;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StepRunRepository extends JpaRepository<StepRun, Long> {

    List<StepRun> findByScenarioRunId(Long scenarioRunId);

    Optional<StepRun> findByScenarioRunIdAndStepId(Long scenarioRunId, Long stepId);
}
