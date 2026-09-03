package com.rpatest.scenario.repository;

import com.rpatest.scenario.domain.ScenarioStep;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ScenarioStepRepository extends JpaRepository<ScenarioStep, Long> {

    List<ScenarioStep> findByScenarioIdOrderByPosition(Long scenarioId);

    void deleteByScenarioId(Long scenarioId);
}
