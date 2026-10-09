package com.rpatest.execution.report;

import static com.rpatest.execution.report.ReportFixtures.report;
import static com.rpatest.execution.report.ReportFixtures.step;
import static org.assertj.core.api.Assertions.assertThat;

import com.rpatest.execution.domain.RunStatus;
import com.rpatest.scenario.domain.ScenarioStepType;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class RunReportHtmlRendererTest {

    @Test
    void rendersHeaderVerdictAndAllSectionsForASuccessfulRun() {
        String html = RunReportHtmlRenderer.render(ReportFixtures.succeededChain());

        assertThat(html).startsWith("<!DOCTYPE html>").contains("lang=\"ru\"");
        assertThat(html).contains("Отчёт о тестировании").contains("УСПЕШНО").contains("Сверка платежей")
                .contains("№12").contains("ivanov").contains("6 мин 23 с")
                .contains("07.10.2026 20:40:00").contains("07.10.2026 20:46:23");
        assertThat(html).contains("Ход выполнения").contains("Хронология").contains("<svg").contains("Проверки очередей")
                .contains("Задания").contains("Очереди");
        assertThat(html).contains("robot-7").contains("Obrabotka_12_2").contains("07.10.2026 20:41:10");
        assertThat(html).contains("Пройдена").contains("&#10003; выполнено").contains("out_q");
        assertThat(html).doesNotContain("Причина ошибки");
    }

    @Test
    void failedRunShowsTheFirstFailedStepAsTheCause() {
        RunReportSnapshot failed = report(RunStatus.FAILED, "S", List.of(
                step(1, "Задание А", ScenarioStepType.JOB, RunStatus.FAILED, 10L, "Assignment failed on robot 'r1'", null),
                step(2, "Задание Б", ScenarioStepType.JOB, RunStatus.FAILED, 10L, "later fallout", null),
                step(3, "Проверка", ScenarioStepType.QUEUE_CHECK, RunStatus.PENDING, null, null, null)), List.of());

        String html = RunReportHtmlRenderer.render(failed);

        assertThat(html).contains("ЕСТЬ ОШИБКИ").contains("Причина ошибки").contains("Assignment failed on robot &#39;r1&#39;");
        assertThat(html).contains("Нет данных").contains("Не выполнялся: один из предыдущих шагов завершился с ошибкой.");
        assertThat(html).contains("Шаг завершился с ошибкой. Подробности - в технических деталях.")
                .contains("Технические детали");
        int cause = html.indexOf("Причина ошибки");
        assertThat(html.substring(cause, html.indexOf("</div>", cause))).contains("r1").doesNotContain("later fallout");
    }

    @Test
    void failedQueueCheckShowsHowManyAreMissing() {
        RunReportSnapshot failed = report(RunStatus.FAILED, "S", List.of(
                step(1, "Проверка", ScenarioStepType.QUEUE_CHECK, RunStatus.FAILED, 5L, "boom", Map.of(
                        "queueName", "q", "expected", Map.of("SUCCESS", 5, "ERROR", 0), "actual", Map.of("SUCCESS", 2),
                        "actualTotal", 2, "passed", false, "transactions", List.of(), "transactionsTotal", 2))), List.of());

        String html = RunReportHtmlRenderer.render(failed);

        assertThat(html).contains("Не пройдена").contains("&#10007; не хватает 3").contains("&#10003; выполнено");
    }

    @Test
    void escapesEverythingThatComesFromUsersAndTheOrchestrator() {
        String evil = "<script>alert(1)</script>";
        RunReportSnapshot report = report(RunStatus.FAILED, evil, List.of(
                step(1, evil, ScenarioStepType.QUEUE_CHECK, RunStatus.FAILED, 1L, evil, Map.of(
                        "queueName", evil, "expected", Map.of(), "actual", Map.of(), "passed", false,
                        "transactions", List.of(Map.of("naturalKey", evil, "status", evil, "retray", 0)),
                        "transactionsTotal", 1)),
                step(2, "job", ScenarioStepType.JOB, RunStatus.FAILED, 1L, evil, Map.of(
                        "assignmentName", evil, "projectName", evil, "robotName", evil, "success", false)),
                step(3, "q", ScenarioStepType.QUEUE, RunStatus.SUCCEEDED, 1L, null, Map.of("queueName", evil))),
                List.of(new RunReportSnapshot.Edge(1L, 2L)));

        String html = RunReportHtmlRenderer.render(report);

        assertThat(html).doesNotContain("<script");
        assertThat(html).contains("&lt;script&gt;alert(1)&lt;/script&gt;");
    }

    @Test
    void rendersTheStructureMapTheTimelineAndALegend() {
        String html = RunReportHtmlRenderer.render(ReportFixtures.succeededChain());

        assertThat(html).contains("Ход выполнения").contains("Хронология").contains("class=\"legend\"")
                .contains("Создание очереди").contains("Проверка очереди").contains("Задание")
                .contains("сверху вниз").contains("размер - длительность");
        assertThat(count(html, "role=\"img\"")).isEqualTo(2);
        assertThat(html).contains("<title>2. Обработка - Успешно");
    }

    @Test
    void emptyRunRendersWithoutDiagramInsteadOfFailing() {
        String html = RunReportHtmlRenderer.render(report(RunStatus.SUCCEEDED, "S", List.of(), List.of()));

        assertThat(html).contains("В прогоне нет шагов").doesNotContain("role=\"img\"");
    }

    private static int count(String text, String part) {
        return text.split(Pattern.quote(part), -1).length - 1;
    }
}
