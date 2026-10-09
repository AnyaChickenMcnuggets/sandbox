package com.rpatest.execution.repository;

import com.rpatest.execution.domain.RunReport;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RunReportRepository extends JpaRepository<RunReport, Long> {
}
