package com.rpatest.execution.report;

import com.rpatest.execution.domain.RunStatus;
import com.rpatest.scenario.domain.ScenarioStepType;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

final class ReportFixtures {

    static final OffsetDateTime START = ZonedDateTime.of(2026, 10, 7, 20, 40, 0, 0, ZoneId.systemDefault()).toOffsetDateTime();
    static final OffsetDateTime END = ZonedDateTime.of(2026, 10, 7, 20, 46, 23, 0, ZoneId.systemDefault()).toOffsetDateTime();

    private ReportFixtures() {
    }

    static RunReportSnapshot.Step step(
            long id, String name, ScenarioStepType type, RunStatus status, Long seconds, String error, Map<String, Object> result) {
        boolean notRun = status == RunStatus.PENDING;
        return new RunReportSnapshot.Step(id, name, type, status,
                notRun ? null : START.plusSeconds(id),
                notRun ? null : START.plusSeconds(id + (seconds == null ? 0 : seconds)),
                seconds, "detail of " + name, error, result);
    }

    static RunReportSnapshot report(
            RunStatus status, String scenarioName, List<RunReportSnapshot.Step> steps, List<RunReportSnapshot.Edge> edges) {
        return new RunReportSnapshot(12L, 5L, scenarioName, "ivanov", status, START, END, 383L, null, steps, edges, END);
    }

    /** queue -> job -> check, the shape of a typical scenario. */
    static RunReportSnapshot succeededChain() {
        return report(RunStatus.SUCCEEDED, "Сверка платежей", List.of(
                step(1, "Входная очередь", ScenarioStepType.QUEUE, RunStatus.SUCCEEDED, 4L,
                        null, Map.of("queueName", "in_q", "created", true, "transactionsAdded", 3)),
                step(2, "Обработка", ScenarioStepType.JOB, RunStatus.SUCCEEDED, 300L, null, Map.of(
                        "assignmentName", "Obrabotka_12_2", "projectName", "Payments", "robotName", "robot-7",
                        "robotStartedAt", "2026-10-07T20:41:10", "completedAt", "2026-10-07T20:45:50", "success", true)),
                step(3, "Проверка результата", ScenarioStepType.QUEUE_CHECK, RunStatus.SUCCEEDED, 20L, null, Map.of(
                        "queueName", "out_q", "expected", Map.of("SUCCESS", 3), "minTotalCount", 3,
                        "actual", Map.of("SUCCESS", 3), "actualTotal", 3, "passed", true,
                        "transactions", List.of(Map.of("naturalKey", "k1", "status", "SUCCESS", "retray", 0)),
                        "transactionsTotal", 3))),
                List.of(new RunReportSnapshot.Edge(1L, 2L), new RunReportSnapshot.Edge(2L, 3L)));
    }
}
