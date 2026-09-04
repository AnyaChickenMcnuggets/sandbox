package com.rpatest.orchestrator.client;

import com.rpatest.orchestrator.dto.RpaProjectLaunchDto;
import java.util.List;

public interface RpaProjectLaunchesPort {

    /** Реальные запуски проекта на роботах для данного задания (обычно 0 или 1, но не гарантировано). */
    List<RpaProjectLaunchDto> getByAssignment(int assignmentId);
}
