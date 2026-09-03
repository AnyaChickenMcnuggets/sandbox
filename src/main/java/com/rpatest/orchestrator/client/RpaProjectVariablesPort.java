package com.rpatest.orchestrator.client;

import com.rpatest.orchestrator.dto.RpaProjectVariableDto;
import com.rpatest.orchestrator.dto.RpaProjectVariableEditByIdDto;
import java.util.List;

public interface RpaProjectVariablesPort {

    List<RpaProjectVariableDto> get(int assignmentId);

    void update(int assignmentId, List<RpaProjectVariableEditByIdDto> values);
}
