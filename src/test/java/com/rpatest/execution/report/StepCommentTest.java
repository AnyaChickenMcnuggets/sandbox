package com.rpatest.execution.report;

import static com.rpatest.execution.report.ReportFixtures.report;
import static com.rpatest.execution.report.ReportFixtures.step;
import static org.assertj.core.api.Assertions.assertThat;

import com.rpatest.execution.domain.RunStatus;
import com.rpatest.scenario.domain.ScenarioStepType;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class StepCommentTest {

    private static RunReportSnapshot.Step stepWith(
            long id, String name, ScenarioStepType type, RunStatus status, String detail, String error) {
        boolean notRun = status == RunStatus.PENDING;
        OffsetDateTime start = notRun ? null : ReportFixtures.START.plusSeconds(id);
        return new RunReportSnapshot.Step(id, name, type, status, start, start == null ? null : start.plusSeconds(5), notRun ? null : 5L,
                detail, error, null);
    }

    private static RunReportSnapshot.Edge edge(long from, long to) {
        return new RunReportSnapshot.Edge(from, to);
    }

    private static StepComment.Comment comment(RunReportSnapshot report, int index) {
        return StepComment.describe(report, report.steps().get(index));
    }

    @Test
    void aFinishedStepShowsItsLastProgressTextTranslatedAndKeepsTheOriginal() {
        RunReportSnapshot report = report(RunStatus.SUCCEEDED, "S", List.of(
                stepWith(1, "check", ScenarioStepType.QUEUE_CHECK, RunStatus.SUCCEEDED,
                        "Queue check 'q' passed: total=3 SUCCESS=3 ", null)), List.of());

        StepComment.Comment comment = comment(report, 0);

        assertThat(comment.text()).isEqualTo("Проверка очереди «q» пройдена: всего 3 (Успешно: 3)");
        assertThat(comment.technical()).isEqualTo("Queue check 'q' passed: total=3 SUCCESS=3 ");
    }

    @Test
    void aFailedStepShowsTheTranslatedErrorInFullInsteadOfTheProgressText() {
        RunReportSnapshot report = report(RunStatus.FAILED, "S", List.of(
                stepWith(1, "job", ScenarioStepType.JOB, RunStatus.FAILED, "Assignment 'J' running on robot 'r9' (started 2026-10-07T20:41:10)"
                        + " (attempt #3)", "Assignment failed on robot 'r9': Не удалось открыть приложение")), List.of());

        StepComment.Comment comment = comment(report, 0);

        assertThat(comment.text()).isEqualTo("Задание завершилось с ошибкой на роботе «r9»: Не удалось открыть приложение");
        assertThat(comment.technical()).isEqualTo("Assignment failed on robot 'r9': Не удалось открыть приложение");
    }

    @Test
    void aRunRecordedBeforeTheTranslationIsPrettifiedWithoutADuplicate() {
        RunReportSnapshot report = report(RunStatus.SUCCEEDED, "S", List.of(
                stepWith(1, "check", ScenarioStepType.QUEUE_CHECK, RunStatus.SUCCEEDED,
                        "Проверка очереди 'out_q' пройдена: всего=3 SUCCESS=3 ", null)), List.of());

        StepComment.Comment comment = comment(report, 0);

        assertThat(comment.text()).isEqualTo("Проверка очереди «out_q» пройдена: всего 3 (Успешно: 3)");
        assertThat(comment.technical()).isNull();
    }

    @Test
    void textThatNeedsNoTranslationHasNoTechnicalDuplicate() {
        RunReportSnapshot report = report(RunStatus.STOPPED, "S", List.of(
                stepWith(1, "job", ScenarioStepType.JOB, RunStatus.FAILED, null, "Остановлено пользователем")), List.of());

        StepComment.Comment comment = comment(report, 0);

        assertThat(comment.text()).isEqualTo("Остановлено пользователем");
        assertThat(comment.technical()).isNull();
    }

    @Test
    void aStepWithNoStoredTextGetsAPlainSentence() {
        RunReportSnapshot report = report(RunStatus.FAILED, "S", List.of(
                stepWith(1, "ok", ScenarioStepType.JOB, RunStatus.SUCCEEDED, null, null),
                stepWith(2, "bad", ScenarioStepType.JOB, RunStatus.FAILED, null, null)), List.of());

        assertThat(comment(report, 0).text()).isEqualTo("Выполнен успешно.");
        assertThat(comment(report, 1).text()).isEqualTo("Шаг завершился с ошибкой, текст ошибки не сохранён.");
    }

    @Test
    void stepsBeforeThePointOfStartOfARunFromTheMiddleAreNotBlamedOnAFailure() {
        // 1 -> 2 -> 3 -> 4: run started at step 3; 3 succeeded, 4 failed; 1 and 2 were never part of the run
        RunReportSnapshot report = new RunReportSnapshot(12L, 5L, "S", "u", RunStatus.FAILED, ReportFixtures.START, ReportFixtures.END,
                10L, 33L, List.of(
                        stepWith(1, "first", ScenarioStepType.QUEUE, RunStatus.PENDING, null, null),
                        stepWith(2, "second", ScenarioStepType.JOB, RunStatus.PENDING, null, null),
                        stepWith(3, "third", ScenarioStepType.JOB, RunStatus.SUCCEEDED, "done", null),
                        stepWith(4, "fourth", ScenarioStepType.QUEUE_CHECK, RunStatus.FAILED, null, "boom")),
                List.of(edge(1, 2), edge(2, 3), edge(3, 4)), ReportFixtures.END);

        assertThat(comment(report, 0).text()).isEqualTo("Не выполнялся: прогон начат с середины сценария, этот шаг расположен до точки старта.");
        assertThat(comment(report, 1).text()).isEqualTo("Не выполнялся: прогон начат с середины сценария, этот шаг расположен до точки старта.");
        assertThat(comment(report, 0).text()).doesNotContain("завершился с ошибкой");
    }

    @Test
    void aStepBehindAFailedStepNamesTheFailedOneEvenThroughSkippedSteps() {
        RunReportSnapshot report = report(RunStatus.FAILED, "S", List.of(
                stepWith(1, "ok", ScenarioStepType.QUEUE, RunStatus.SUCCEEDED, "x", null),
                stepWith(2, "broken", ScenarioStepType.JOB, RunStatus.FAILED, null, "boom"),
                stepWith(3, "next", ScenarioStepType.QUEUE_CHECK, RunStatus.PENDING, null, null),
                stepWith(4, "last", ScenarioStepType.QUEUE_CHECK, RunStatus.PENDING, null, null)),
                List.of(edge(1, 2), edge(2, 3), edge(3, 4)));

        assertThat(comment(report, 2).text()).isEqualTo("Не выполнялся: шаг «broken» (№2), от которого он зависит, завершился с ошибкой.");
        assertThat(comment(report, 3).text()).isEqualTo("Не выполнялся: шаг «broken» (№2), от которого он зависит, завершился с ошибкой.");
    }

    @Test
    void aSiblingBranchThatDidNotDependOnTheFailureIsNotBlamedOnIt() {
        // 1 -> 2 (fails) and 1 -> 3 (never ran because the run was stopped)
        RunReportSnapshot report = report(RunStatus.STOPPED, "S", List.of(
                stepWith(1, "root", ScenarioStepType.QUEUE, RunStatus.SUCCEEDED, "x", null),
                stepWith(2, "broken", ScenarioStepType.JOB, RunStatus.FAILED, null, "Остановлено пользователем"),
                stepWith(3, "other", ScenarioStepType.JOB, RunStatus.PENDING, null, null)),
                List.of(edge(1, 2), edge(1, 3)));

        assertThat(comment(report, 2).text()).isEqualTo("Не выполнялся: прогон был остановлен.");
    }

    @Test
    void aStepOutsideAStartFromTheMiddleRunSaysSo() {
        // 1 -> 2 and an unrelated 3: the run started at 2, nothing failed
        RunReportSnapshot report = new RunReportSnapshot(12L, 5L, "S", "u", RunStatus.SUCCEEDED, ReportFixtures.START, ReportFixtures.END,
                10L, 22L, List.of(
                        stepWith(1, "before", ScenarioStepType.QUEUE, RunStatus.PENDING, null, null),
                        stepWith(2, "start", ScenarioStepType.JOB, RunStatus.SUCCEEDED, "x", null),
                        stepWith(3, "unrelated", ScenarioStepType.JOB, RunStatus.PENDING, null, null)),
                List.of(edge(1, 2)), ReportFixtures.END);

        assertThat(comment(report, 2).text()).isEqualTo("Не выполнялся: шаг не входит в запуск с середины сценария.");
    }

    @Test
    void aNotRunStepInAnOrdinaryRunHasAPlainReasonAndACycleDoesNotHang() {
        RunReportSnapshot plain = report(RunStatus.SUCCEEDED, "S", List.of(
                step(1, "lonely", ScenarioStepType.JOB, RunStatus.PENDING, null, null, null)), List.of());
        RunReportSnapshot cyclic = report(RunStatus.FAILED, "S", List.of(
                stepWith(1, "a", ScenarioStepType.JOB, RunStatus.PENDING, null, null),
                stepWith(2, "b", ScenarioStepType.JOB, RunStatus.PENDING, null, null)), List.of(edge(1, 2), edge(2, 1)));

        assertThat(comment(plain, 0).text()).isEqualTo("Не выполнялся.");
        assertThat(comment(cyclic, 0).text()).isEqualTo("Не выполнялся.");
    }
}
