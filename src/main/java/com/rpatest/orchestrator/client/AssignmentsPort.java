package com.rpatest.orchestrator.client;

import com.rpatest.orchestrator.dto.AssignmentCreateDto;
import com.rpatest.orchestrator.dto.AssignmentDto;
import java.util.List;

public interface AssignmentsPort {

    AssignmentDto create(AssignmentCreateDto request);

    List<AssignmentDto> list();

    AssignmentDto get(int assignmentId);

    void start(int assignmentId);

    void stop(int assignmentId);

    void delete(int assignmentId);
}
