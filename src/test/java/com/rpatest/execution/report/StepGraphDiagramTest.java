package com.rpatest.execution.report;

import static com.rpatest.execution.report.ReportFixtures.step;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.rpatest.execution.domain.RunStatus;
import com.rpatest.scenario.domain.ScenarioStepType;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class StepGraphDiagramTest {

    private static RunReportSnapshot.Step block(long id, ScenarioStepType type, RunStatus status, Long seconds) {
        return step(id, "step " + id, type, status, seconds, null, null);
    }

    private static RunReportSnapshot.Edge edge(long from, long to) {
        return new RunReportSnapshot.Edge(from, to);
    }

    private static int count(String text, String part) {
        return text.split(Pattern.quote(part), -1).length - 1;
    }

    private static int[] viewBox(String svg) {
        Matcher m = Pattern.compile("viewBox=\"0 0 (\\d+) (\\d+)\"").matcher(svg);
        assertThat(m.find()).isTrue();
        return new int[] {Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))};
    }

    private static List<String> texts(String svg) {
        List<String> texts = new ArrayList<>();
        Matcher m = Pattern.compile("<text[^>]*>([^<]*)</text>").matcher(svg);
        while (m.find()) {
            texts.add(m.group(1));
        }
        return texts;
    }

    private static List<Double> circleRadii(String svg) {
        List<Double> radii = new ArrayList<>();
        Matcher m = Pattern.compile("<circle [^>]*r=\"([0-9.]+)\"").matcher(svg);
        while (m.find()) {
            radii.add(Double.parseDouble(m.group(1)));
        }
        return radii;
    }

    @Test
    void shapeFollowsTheStepType() {
        List<RunReportSnapshot.Step> steps = List.of(
                block(1, ScenarioStepType.QUEUE, RunStatus.SUCCEEDED, 5L),
                block(2, ScenarioStepType.JOB, RunStatus.SUCCEEDED, 5L),
                block(3, ScenarioStepType.QUEUE_CHECK, RunStatus.SUCCEEDED, 5L));

        String svg = StepGraphDiagram.render(steps, List.of(edge(1, 2), edge(2, 3)));

        assertThat(count(svg, "<circle ")).isEqualTo(1);
        assertThat(count(svg, "<rect ")).isEqualTo(1);
        // the job is a triangle pointing down: a wide edge on top, the apex below the centre
        Matcher triangle = Pattern.compile("<path d=\"M([0-9.]+),([0-9.]+) L([0-9.]+),([0-9.]+) L([0-9.]+),([0-9.]+) Z\"").matcher(svg);
        assertThat(triangle.find()).isTrue();
        assertThat(triangle.group(2)).isEqualTo(triangle.group(4));
        assertThat(Double.parseDouble(triangle.group(6))).isGreaterThan(Double.parseDouble(triangle.group(2)));
        assertThat(Double.parseDouble(triangle.group(5))).isBetween(Double.parseDouble(triangle.group(1)), Double.parseDouble(triangle.group(3)));
    }

    @Test
    void theDiagramHoldsNoTextExceptTheBlockNumbers() {
        RunReportSnapshot report = ReportFixtures.succeededChain();

        String svg = StepGraphDiagram.render(report.steps(), report.edges());

        assertThat(texts(svg)).containsExactly("1", "2", "3");
    }

    @Test
    void everyBlockLinksToItsRowInTheStepsTable() {
        RunReportSnapshot report = ReportFixtures.succeededChain();

        String svg = StepGraphDiagram.render(report.steps(), report.edges());

        assertThat(svg).contains("<a href=\"#step-1\">").contains("<a href=\"#step-2\">").contains("<a href=\"#step-3\">");
        String html = RunReportHtmlRenderer.render(report);
        assertThat(html).contains("<tr id=\"step-1\">").contains("<tr id=\"step-2\">").contains("<tr id=\"step-3\">");
    }

    @Test
    void blockSizeIsScaledBetweenTheQuickestAndTheSlowestStep() {
        List<RunReportSnapshot.Step> steps = List.of(
                block(1, ScenarioStepType.QUEUE, RunStatus.SUCCEEDED, 4L),
                block(2, ScenarioStepType.QUEUE, RunStatus.SUCCEEDED, 30L),
                block(3, ScenarioStepType.QUEUE, RunStatus.SUCCEEDED, 300L),
                block(4, ScenarioStepType.QUEUE, RunStatus.PENDING, null));

        List<Double> radii = circleRadii(StepGraphDiagram.render(steps, List.of()));

        assertThat(radii).hasSize(4);
        assertThat(radii.get(0)).isEqualTo(10.0);
        assertThat(radii.get(2)).isEqualTo(30.0);
        assertThat(radii.get(1)).isStrictlyBetween(10.0, 30.0);
        assertThat(radii.get(3)).isEqualTo(10.0);
        // the slowest block towers over the quickest one
        assertThat(radii.get(2)).isGreaterThanOrEqualTo(3 * radii.get(0));
    }

    @Test
    void stepsBetweenTheExtremesStayDistinguishableNextToOneSlowOutlier() {
        // 10 s, 20 s, 40 s and one 10-minute step: a linear scale would draw the first three alike
        List<RunReportSnapshot.Step> steps = List.of(
                block(1, ScenarioStepType.QUEUE, RunStatus.SUCCEEDED, 10L), block(2, ScenarioStepType.QUEUE, RunStatus.SUCCEEDED, 20L),
                block(3, ScenarioStepType.QUEUE, RunStatus.SUCCEEDED, 40L), block(4, ScenarioStepType.QUEUE, RunStatus.SUCCEEDED, 600L));

        List<Double> radii = circleRadii(StepGraphDiagram.render(steps, List.of()));

        assertThat(radii.get(1) - radii.get(0)).isGreaterThanOrEqualTo(3.0);
        assertThat(radii.get(2) - radii.get(1)).isGreaterThanOrEqualTo(3.0);
        assertThat(radii.get(3)).isEqualTo(30.0);
    }

    @Test
    void stepsOfEqualDurationGetTheSameMiddleSize() {
        List<RunReportSnapshot.Step> steps = List.of(
                block(1, ScenarioStepType.QUEUE, RunStatus.SUCCEEDED, 60L), block(2, ScenarioStepType.QUEUE, RunStatus.SUCCEEDED, 60L));

        assertThat(circleRadii(StepGraphDiagram.render(steps, List.of()))).containsExactly(20.0, 20.0);
    }

    @Test
    void aSingleExecutedStepAmongSkippedOnesIsNotTheSmallest() {
        List<RunReportSnapshot.Step> steps = List.of(
                block(1, ScenarioStepType.QUEUE, RunStatus.SUCCEEDED, 60L), block(2, ScenarioStepType.QUEUE, RunStatus.PENDING, null));

        List<Double> radii = circleRadii(StepGraphDiagram.render(steps, List.of()));

        assertThat(radii.get(0)).isGreaterThan(radii.get(1));
    }

    @Test
    void executionGoesTopToBottomAndParallelStepsShareARow() {
        // 1 -> (2, 3) -> 4
        List<RunReportSnapshot.Step> steps = List.of(
                block(1, ScenarioStepType.QUEUE, RunStatus.SUCCEEDED, 10L), block(2, ScenarioStepType.QUEUE, RunStatus.SUCCEEDED, 10L),
                block(3, ScenarioStepType.QUEUE, RunStatus.SUCCEEDED, 10L), block(4, ScenarioStepType.QUEUE, RunStatus.SUCCEEDED, 10L));

        String svg = StepGraphDiagram.render(steps, List.of(edge(1, 2), edge(1, 3), edge(2, 4), edge(3, 4)));

        List<double[]> centres = new ArrayList<>();
        Matcher m = Pattern.compile("<circle cx=\"([0-9.]+)\" cy=\"([0-9.]+)\"").matcher(svg);
        while (m.find()) {
            centres.add(new double[] {Double.parseDouble(m.group(1)), Double.parseDouble(m.group(2))});
        }
        assertThat(centres).hasSize(4);
        assertThat(centres.get(1)[1]).isEqualTo(centres.get(2)[1]);
        assertThat(centres.get(1)[0]).isNotEqualTo(centres.get(2)[0]);
        assertThat(centres.get(0)[1]).isLessThan(centres.get(1)[1]);
        assertThat(centres.get(1)[1]).isLessThan(centres.get(3)[1]);
    }

    @Test
    void aChainOfFortyIsOneLaneAndSevenBranchesShareRowsAndFillTheWidth() {
        List<RunReportSnapshot.Step> chain = new ArrayList<>();
        List<RunReportSnapshot.Edge> chainEdges = new ArrayList<>();
        for (long i = 1; i <= 40; i++) {
            chain.add(block(i, ScenarioStepType.JOB, RunStatus.SUCCEEDED, 5L + i));
            if (i > 1) {
                chainEdges.add(edge(i - 1, i));
            }
        }
        List<RunReportSnapshot.Step> wide = new ArrayList<>();
        List<RunReportSnapshot.Edge> wideEdges = new ArrayList<>();
        wide.add(block(1, ScenarioStepType.QUEUE, RunStatus.SUCCEEDED, 5L));
        long id = 2;
        List<Long> tails = new ArrayList<>();
        for (int branch = 0; branch < 7; branch++) {
            long previous = 1;
            for (int k = 0; k < 5; k++) {
                wide.add(block(id, ScenarioStepType.JOB, RunStatus.SUCCEEDED, 30L + 10 * k));
                wideEdges.add(edge(previous, id));
                previous = id++;
            }
            tails.add(previous);
        }
        wide.add(block(id, ScenarioStepType.QUEUE_CHECK, RunStatus.PENDING, null));
        long join = id;
        tails.forEach(t -> wideEdges.add(edge(t, join)));

        int[] chainBox = viewBox(StepGraphDiagram.render(chain, chainEdges));
        int[] wideBox = viewBox(StepGraphDiagram.render(wide, wideEdges));

        // one lane stays a single column, seven lanes are spread over the whole width of the page block
        assertThat(chainBox[0]).isLessThan(400);
        assertThat(wideBox[0]).isBetween(950, 1100);
        // 7 branches of 5 steps share 5 rows (+ the root and the join), so they are far shorter than 38 rows
        assertThat(wideBox[1]).isLessThan(chainBox[1] / 2);
    }

    @Test
    void lanesAreSpreadEvenlyAcrossTheWidth() {
        List<RunReportSnapshot.Step> steps = List.of(block(1, ScenarioStepType.QUEUE, RunStatus.SUCCEEDED, 5L),
                block(2, ScenarioStepType.QUEUE, RunStatus.SUCCEEDED, 5L), block(3, ScenarioStepType.QUEUE, RunStatus.SUCCEEDED, 5L),
                block(4, ScenarioStepType.QUEUE, RunStatus.SUCCEEDED, 5L));

        String svg = StepGraphDiagram.render(steps, List.of(edge(1, 2), edge(1, 3), edge(1, 4)));

        List<Double> xs = new ArrayList<>();
        Matcher m = Pattern.compile("<circle cx=\"([0-9.]+)\"").matcher(svg);
        while (m.find()) {
            xs.add(Double.parseDouble(m.group(1)));
        }
        // steps 1 and 2 share the main lane; 3 and 4 open two more lanes: three evenly spaced columns,
        // the main lane in the middle
        assertThat(xs).hasSize(4);
        assertThat(xs.get(0)).isEqualTo(xs.get(1));
        double main = xs.get(0);
        double pitch = Math.abs(xs.get(2) - main);
        assertThat(pitch).isGreaterThan(150);
        assertThat(Math.abs(xs.get(3) - main)).isEqualTo(pitch);
        assertThat(xs.get(2)).isNotEqualTo(xs.get(3));
    }

    @Test
    void theMainLaneIsInTheMiddleAndTheOthersAlternateAroundIt() {
        assertThat(StepGraphDiagram.columnOfLane(1)).containsExactly(0);
        assertThat(StepGraphDiagram.columnOfLane(2)).containsExactly(0, 1);
        assertThat(StepGraphDiagram.columnOfLane(7)).containsExactly(3, 4, 2, 5, 1, 6, 0);
        assertThat(StepGraphDiagram.columnOfLane(6)).containsExactlyInAnyOrder(0, 1, 2, 3, 4, 5);
        assertThat(StepGraphDiagram.columnOfLane(12)).containsExactlyInAnyOrder(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11);
    }

    @Test
    void colorsFollowTheStatusAndNotRunStepsAreDashed() {
        List<RunReportSnapshot.Step> steps = List.of(block(1, ScenarioStepType.JOB, RunStatus.FAILED, 5L),
                block(2, ScenarioStepType.JOB, RunStatus.PENDING, null));

        String svg = StepGraphDiagram.render(steps, List.of(edge(1, 2)));

        assertThat(svg).contains(ReportText.statusColor(RunStatus.FAILED)).contains(ReportText.statusColor(RunStatus.PENDING));
        assertThat(svg).contains("stroke-dasharray");
    }

    @Test
    void legendDescribesTheThreeShapes() {
        assertThat(StepGraphDiagram.legend()).contains("Создание очереди").contains("Проверка очереди").contains("Задание");
    }

    @Test
    void ignoresEdgesToUnknownStepsAndSurvivesACycle() {
        List<RunReportSnapshot.Step> steps = List.of(block(1, ScenarioStepType.JOB, RunStatus.SUCCEEDED, 1L),
                block(2, ScenarioStepType.JOB, RunStatus.SUCCEEDED, 1L));

        assertThatCode(() -> StepGraphDiagram.render(steps, List.of(edge(1, 2), edge(2, 1), edge(1, 99)))).doesNotThrowAnyException();
        assertThat(count(StepGraphDiagram.render(steps, List.of(edge(1, 99))), "fill=\"none\"")).isZero();
    }

    @Test
    void escapesNamesInTooltipsAndHandlesAnEmptyRun() {
        RunReportSnapshot.Step evil = step(1, "<script>alert(1)</script>", ScenarioStepType.JOB, RunStatus.SUCCEEDED, 1L, null, null);

        assertThat(StepGraphDiagram.render(List.of(evil), List.of())).doesNotContain("<script").contains("&lt;script&gt;");
        assertThat(StepGraphDiagram.render(List.of(), List.of())).contains("В прогоне нет шагов").doesNotContain("<svg");
    }
}
