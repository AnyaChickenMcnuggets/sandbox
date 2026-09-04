package com.rpatest.orchestrator.client;

import com.rpatest.orchestrator.dto.QueueItemProjectDto;
import java.util.List;

public interface RpaProjectQueuePort {

    /** Записи задания в очереди ожидания запуска проектов оркестратора (RpaProjectQueue). */
    List<QueueItemProjectDto> findByAssignment(int assignmentId);
}
