package com.rpatest.execution.report;

import static com.rpatest.execution.report.ReportFixtures.report;
import static com.rpatest.execution.report.ReportFixtures.step;
import static org.assertj.core.api.Assertions.assertThat;

import com.rpatest.execution.domain.RunStatus;
import com.rpatest.scenario.domain.ScenarioStepType;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StepCommentTest {

    private static StepComment.Comment comment(RunReportSnapshot report, int index) {
        return StepComment.describe(report, report.steps().get(index));
    }

    @Test
    void succeededJobNamesTheRobotAndTheDuration() {
        RunReportSnapshot report = ReportFixtures.succeededChain();

        assertThat(comment(report, 1).text()).isEqualTo("Задание выполнено на роботе «robot-7» за 5 мин 00 с.");
        assertThat(comment(report, 1).technical()).isNull();
    }

    @Test
    void queueStepSaysWhetherTheQueueWasCreatedOrReused() {
        RunReportSnapshot created = ReportFixtures.succeededChain();
        RunReportSnapshot reused = report(RunStatus.SUCCEEDED, "S", List.of(
                step(1, "q", ScenarioStepType.QUEUE, RunStatus.SUCCEEDED, 1L, null,
                        Map.of("queueName", "in_q", "created", false, "transactionsAdded", 0))), List.of());

        assertThat(comment(created, 0).text()).isEqualTo("Очередь «in_q» создана этим прогоном, добавлено транзакций: 3.");
        assertThat(comment(reused, 0).text()).isEqualTo("Использована уже существующая очередь «in_q», добавлено транзакций: 0.");
    }

    @Test
    void passedCheckSummarisesWhatWasReceived() {
        assertThat(comment(ReportFixtures.succeededChain(), 2).text())
                .isEqualTo("Проверка пройдена: получено транзакций: 3 (Успешно - 3).");
    }

    @Test
    void failedCheckListsEveryShortfallAndTheReason() {
        RunReportSnapshot report = report(RunStatus.FAILED, "S", List.of(
                step(1, "check", ScenarioStepType.QUEUE_CHECK, RunStatus.FAILED, 5L,
                        "Queue check 'q' failed: ...", Map.of(
                                "queueName", "q", "expected", Map.of("SUCCESS", 5, "ERROR", 0), "minTotalCount", 7,
                                "actual", Map.of("SUCCESS", 2), "actualTotal", 2, "passed", false,
                                "failureReason", "ALL_FINAL"))), List.of());

        StepComment.Comment comment = comment(report, 0);

        assertThat(comment.text()).isEqualTo("Проверка не пройдена: «Успешно»: ожидалось не менее 5, получено 2; "
                + "всего: ожидалось не менее 7, получено 2. Все отслеживаемые транзакции уже получили конечный статус, "
                + "дальнейшее ожидание не имело смысла.");
        assertThat(comment.technical()).isEqualTo("Queue check 'q' failed: ...");
    }

    @Test
    void failedCheckByTimeoutSaysSo() {
        RunReportSnapshot report = report(RunStatus.FAILED, "S", List.of(
                step(1, "check", ScenarioStepType.QUEUE_CHECK, RunStatus.FAILED, 5L, "x", Map.of(
                        "queueName", "q", "expected", Map.of("SUCCESS", 1), "actual", Map.of(), "actualTotal", 0,
                        "passed", false, "failureReason", "TIMEOUT"))), List.of());

        assertThat(comment(report, 0).text()).endsWith("Истекло время ожидания, заданное в шаге.");
    }

    @Test
    void failedJobQuotesTheRobotMessage() {
        RunReportSnapshot report = report(RunStatus.FAILED, "S", List.of(
                step(1, "job", ScenarioStepType.JOB, RunStatus.FAILED, 5L, "Assignment failed on robot 'r9': boom",
                        Map.of("robotName", "r9", "success", false, "robotError", "Не найден файл"))), List.of());

        StepComment.Comment comment = comment(report, 0);

        assertThat(comment.text()).isEqualTo("Задание завершилось с ошибкой на роботе «r9». Сообщение робота: Не найден файл");
        assertThat(comment.technical()).isEqualTo("Assignment failed on robot 'r9': boom");
    }

    @Test
    void failedStepWithoutStructuredResultFallsBackToAGenericSentenceAndKeepsTheRawError() {
        RunReportSnapshot report = report(RunStatus.FAILED, "S", List.of(
                step(1, "job", ScenarioStepType.JOB, RunStatus.FAILED, 1L, "Step 'job' has neither rpaProjectName nor rpaProjectId", null),
                step(2, "job2", ScenarioStepType.JOB, RunStatus.FAILED, 1L, null, null)), List.of());

        assertThat(comment(report, 0).text()).isEqualTo("Шаг завершился с ошибкой. Подробности - в технических деталях.");
        assertThat(comment(report, 0).technical()).contains("neither rpaProjectName");
        assertThat(comment(report, 1).text()).isEqualTo("Шаг завершился с ошибкой, текст ошибки не сохранён.");
    }

    @Test
    void stoppedByUserIsNotShownAsAnError() {
        RunReportSnapshot report = report(RunStatus.STOPPED, "S", List.of(
                step(1, "job", ScenarioStepType.JOB, RunStatus.FAILED, 1L, "Остановлено пользователем", null),
                step(2, "next", ScenarioStepType.QUEUE_CHECK, RunStatus.PENDING, null, null, null)), List.of());

        assertThat(comment(report, 0).text()).isEqualTo("Остановлено пользователем.");
        assertThat(comment(report, 0).technical()).isNull();
        assertThat(comment(report, 1).text()).isEqualTo("Не выполнялся: прогон был остановлен.");
    }

    @Test
    void notRunStepExplainsWhy() {
        List<RunReportSnapshot.Step> pending = List.of(step(1, "a", ScenarioStepType.QUEUE, RunStatus.PENDING, null, null, null));
        RunReportSnapshot afterFailure = report(RunStatus.FAILED, "S", List.of(
                step(1, "a", ScenarioStepType.JOB, RunStatus.FAILED, 1L, "x", null),
                step(2, "b", ScenarioStepType.QUEUE_CHECK, RunStatus.PENDING, null, null, null)), List.of());
        RunReportSnapshot partial = new RunReportSnapshot(12L, 5L, "S", "u", RunStatus.SUCCEEDED, ReportFixtures.START,
                ReportFixtures.END, 1L, 9L, pending, List.of(), ReportFixtures.END);

        assertThat(comment(afterFailure, 1).text()).isEqualTo("Не выполнялся: один из предыдущих шагов завершился с ошибкой.");
        assertThat(comment(partial, 0).text()).isEqualTo("Не выполнялся: прогон начат с середины сценария.");
        assertThat(comment(report(RunStatus.SUCCEEDED, "S", pending, List.of()), 0).text()).isEqualTo("Не выполнялся.");
    }

    @Test
    void succeededStepWithoutResultStillGetsASentence() {
        RunReportSnapshot report = report(RunStatus.SUCCEEDED, "S", List.of(
                step(1, "old", ScenarioStepType.JOB, RunStatus.SUCCEEDED, 1L, null, null)), List.of());

        assertThat(comment(report, 0).text()).isEqualTo("Выполнен успешно.");
    }
}
