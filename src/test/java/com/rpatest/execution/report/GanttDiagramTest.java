package com.rpatest.execution.report;

import static com.rpatest.execution.report.ReportFixtures.report;
import static com.rpatest.execution.report.ReportFixtures.step;
import static org.assertj.core.api.Assertions.assertThat;

import com.rpatest.execution.domain.RunStatus;
import com.rpatest.scenario.domain.ScenarioStepType;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class GanttDiagramTest {

    private static int count(String text, String part) {
        return text.split(Pattern.quote(part), -1).length - 1;
    }

    private static int viewBoxHeight(String svg) {
        Matcher m = Pattern.compile("viewBox=\"0 0 (\\d+) (\\d+)\"").matcher(svg);
        assertThat(m.find()).isTrue();
        return Integer.parseInt(m.group(2));
    }

    @Test
    void oneBarPerExecutedStepAndAPlaceholderForSkippedOnes() {
        RunReportSnapshot report = report(RunStatus.FAILED, "S", List.of(
                step(1, "ok", ScenarioStepType.JOB, RunStatus.SUCCEEDED, 100L, null, null),
                step(2, "bad", ScenarioStepType.JOB, RunStatus.FAILED, 50L, "x", null),
                step(3, "skipped", ScenarioStepType.QUEUE_CHECK, RunStatus.PENDING, null, null, null)), List.of());

        String svg = GanttDiagram.render(report);

        assertThat(count(svg, "<rect ")).isEqualTo(2);
        assertThat(svg).contains("не выполнялся").contains("1. ok").contains("2. bad").contains("3. skipped");
        assertThat(svg).contains(ReportText.statusColor(RunStatus.SUCCEEDED)).contains(ReportText.statusColor(RunStatus.FAILED));
    }

    @Test
    void theAxisCoversTheWholeRun() {
        String svg = GanttDiagram.render(ReportFixtures.succeededChain());

        assertThat(svg).contains(">0 с<").contains("6 мин 23 с");
    }

    @Test
    void fortyStepsStayReadableBecauseRowsGetDenser() {
        List<RunReportSnapshot.Step> few = new ArrayList<>();
        List<RunReportSnapshot.Step> many = new ArrayList<>();
        for (long i = 1; i <= 40; i++) {
            many.add(step(i, "step " + i, ScenarioStepType.JOB, RunStatus.SUCCEEDED, 10L, null, null));
            if (i <= 10) {
                few.add(many.get((int) i - 1));
            }
        }

        int heightMany = viewBoxHeight(GanttDiagram.render(report(RunStatus.SUCCEEDED, "S", many, List.of())));
        int heightFew = viewBoxHeight(GanttDiagram.render(report(RunStatus.SUCCEEDED, "S", few, List.of())));

        assertThat(heightMany).isLessThan(40 * 30);
        assertThat(heightMany).isGreaterThan(heightFew);
    }

    @Test
    void escapesNamesAndHandlesAnEmptyRun() {
        RunReportSnapshot evil = report(RunStatus.SUCCEEDED, "S", List.of(
                step(1, "<script>alert(1)</script>", ScenarioStepType.JOB, RunStatus.SUCCEEDED, 1L, null, null)), List.of());

        assertThat(GanttDiagram.render(evil)).doesNotContain("<script").contains("&lt;script&gt;");
        assertThat(GanttDiagram.render(report(RunStatus.SUCCEEDED, "S", List.of(), List.of()))).contains("В прогоне нет шагов");
    }

    @Test
    void aRunWithoutTimestampsStillRenders() {
        RunReportSnapshot noTimes = new RunReportSnapshot(1L, 1L, "S", "u", RunStatus.STOPPED, null, null, null, null,
                List.of(new RunReportSnapshot.Step(1L, "a", ScenarioStepType.JOB, RunStatus.FAILED, null, null, null, null, null, null)),
                List.of(), null);

        assertThat(GanttDiagram.render(noTimes)).contains("не выполнялся");
    }
}
