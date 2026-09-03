package com.rpatest.scenario.repository;

import com.rpatest.scenario.domain.TestScenario;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TestScenarioRepository extends JpaRepository<TestScenario, Long> {
}
