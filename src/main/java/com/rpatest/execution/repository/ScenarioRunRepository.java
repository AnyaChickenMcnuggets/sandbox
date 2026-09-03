package com.rpatest.execution.repository;

import com.rpatest.execution.domain.ScenarioRun;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ScenarioRunRepository extends JpaRepository<ScenarioRun, Long> {

    List<ScenarioRun> findByScenarioIdOrderByIdDesc(Long scenarioId);

    Optional<ScenarioRun> findFirstByScenarioIdOrderByIdDesc(Long scenarioId);
}
