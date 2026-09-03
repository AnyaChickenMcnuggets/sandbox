package com.rpatest.scenario.repository;

import com.rpatest.scenario.domain.ScenarioStepEdge;
import com.rpatest.scenario.domain.ScenarioStepEdgeId;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ScenarioStepEdgeRepository extends JpaRepository<ScenarioStepEdge, ScenarioStepEdgeId> {

    @Query("select e from ScenarioStepEdge e where e.id.fromStepId in :stepIds or e.id.toStepId in :stepIds")
    List<ScenarioStepEdge> findByStepIds(@Param("stepIds") List<Long> stepIds);
}
