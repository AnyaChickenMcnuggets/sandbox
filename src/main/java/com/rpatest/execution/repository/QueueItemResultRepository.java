package com.rpatest.execution.repository;

import com.rpatest.execution.domain.QueueItemResult;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface QueueItemResultRepository extends JpaRepository<QueueItemResult, Long> {

    List<QueueItemResult> findByStepRunId(Long stepRunId);
}
