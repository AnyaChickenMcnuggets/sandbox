package com.rpatest.execution.report;

import com.rpatest.execution.domain.RunStatus;
import com.rpatest.scenario.domain.ScenarioStepType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.web.util.HtmlUtils;

/**
 * The run as a top-to-bottom graph, git-history style, with no text in it except the number inside
 * every block. Execution goes down; a row is one depth of the DAG, so parallel steps share a row;
 * parallel branches are vertical colored lanes that fork and merge with smooth bends. A block's
 * shape is the step type (circle - queue creation, square - queue check, downward triangle - job),
 * its fill is the status, its size is the duration (logarithmic between the quickest and the
 * slowest executed step, so the slowest block towers over the quickest one, the steps in between
 * stay distinguishable, and every block still fits its number). The number is the step's row in the "Steps"
 * table, and the block links to that row ({@code #step-N}); names and details live in the table
 * and in the hover tooltip.
 *
 * <p>Size follows the scenario in the cheap directions: a chain of 40 steps is 40 short rows in a
 * single narrow lane, 7 parallel branches are 7 lanes sharing the same rows.
 */
final class StepGraphDiagram {

    private static final int PAD = 12;
    /** Radius of the quickest step: still holds a two-digit number. */
    private static final double MIN_R = 10;
    /** Radius of the slowest step: three times the smallest, so it stands out at a glance. */
    private static final double MAX_R = 30;
    private static final double TRIANGLE_FACTOR = 1.2;
    /** The graph is laid out for this width and scaled to the page by the SVG itself. */
    private static final int TARGET_W = 1000;
    private static final int MIN_LANE_W = (int) (2 * MAX_R * TRIANGLE_FACTOR) + 10;
    private static final int MAX_LANE_W = 320;
    /** Gap between rows: a chain is packed tight, parallel branches need room for the forks and merges to bend. */
    private static final int ROW_GAP_CHAIN = 8;
    private static final int ROW_GAP_BRANCHES = 24;
    private static final String[] LANE_COLORS = {
            "#3b82f6", "#a855f7", "#14b8a6", "#f59e0b", "#ec4899", "#64748b", "#84cc16", "#06b6d4"};

    private StepGraphDiagram() {
    }

    /** What the blocks mean - shown next to the diagram, not inside it. */
    static String legend() {
        return "<p class=\"legend\">"
                + "<span>" + icon("<circle r=\"6\"/>") + " Создание очереди</span>"
                + "<span>" + icon("<rect x=\"-6\" y=\"-6\" width=\"12\" height=\"12\" rx=\"1\"/>") + " Проверка очереди</span>"
                + "<span>" + icon("<path d=\"M-7,-6 L7,-6 L0,7 Z\"/>") + " Задание</span></p>\n";
    }

    private static String icon(String shape) {
        return "<svg width=\"15\" height=\"15\" viewBox=\"-8 -8 16 16\" fill=\"#7b8794\" aria-hidden=\"true\">" + shape + "</svg>";
    }

    static String render(List<RunReportSnapshot.Step> steps, List<RunReportSnapshot.Edge> edges) {
        if (steps.isEmpty()) {
            return "<p class=\"muted\">В прогоне нет шагов.</p>";
        }
        Map<Long, Integer> numberOf = new HashMap<>();
        Map<Long, RunReportSnapshot.Step> byId = new LinkedHashMap<>();
        for (RunReportSnapshot.Step step : steps) {
            byId.put(step.id(), step);
            numberOf.put(step.id(), byId.size());
        }
        List<RunReportSnapshot.Edge> valid = edges.stream()
                .filter(e -> byId.containsKey(e.from()) && byId.containsKey(e.to())).distinct().toList();
        Map<Long, Integer> layerOf = DagLayers.compute(byId.keySet(), valid);
        Map<Long, Integer> laneOf = DagLanes.assign(new ArrayList<>(byId.keySet()), valid, layerOf, numberOf);

        long[] executed = steps.stream().filter(StepGraphDiagram::ran).map(RunReportSnapshot.Step::durationSeconds)
                .mapToLong(Long::longValue).toArray();
        long minDuration = java.util.Arrays.stream(executed).min().orElse(0);
        long maxDuration = java.util.Arrays.stream(executed).max().orElse(0);
        Map<Long, Double> radiusOf = new HashMap<>();
        steps.forEach(st -> radiusOf.put(st.id(), radius(st, minDuration, maxDuration)));

        Map<Integer, List<Long>> rows = new TreeMap<>();
        byId.keySet().forEach(id -> rows.computeIfAbsent(layerOf.get(id), k -> new ArrayList<>()).add(id));
        int lanes = laneOf.values().stream().mapToInt(Integer::intValue).max().orElse(0) + 1;
        int rowGap = lanes > 1 ? ROW_GAP_BRANCHES : ROW_GAP_CHAIN;
        int rowCount = rows.size();
        double[] rowCenter = new double[rowCount];
        double y = PAD;
        int index = 0;
        for (List<Long> row : rows.values()) {
            double extent = row.stream().mapToDouble(id -> extent(byId.get(id), radiusOf.get(id))).max().orElse(MIN_R);
            rowCenter[index++] = y + extent;
            y += 2 * extent + rowGap;
        }
        // Lanes spread over the whole width (but not absurdly far apart for two or three branches)
        int laneW = Math.max(MIN_LANE_W, Math.min(MAX_LANE_W, (TARGET_W - 2 * PAD) / lanes));
        int width = PAD * 2 + lanes * laneW;
        int[] column = columnOfLane(lanes);
        int height = (int) Math.ceil(y - rowGap + PAD);

        Map<Long, Integer> rowOf = new HashMap<>();
        index = 0;
        for (List<Long> row : rows.values()) {
            for (Long id : row) {
                rowOf.put(id, index);
            }
            index++;
        }
        Map<Integer, Integer> firstRowOfLane = new HashMap<>();
        byId.keySet().forEach(id -> firstRowOfLane.merge(laneOf.get(id), rowOf.get(id), Math::min));

        StringBuilder svg = new StringBuilder();
        svg.append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 ").append(width).append(' ').append(height)
                .append("\" width=\"100%\" style=\"display:block;margin:0 auto;max-width:").append(width)
                .append("px\" role=\"img\" aria-label=\"Ход выполнения шагов сверху вниз\">\n");
        for (RunReportSnapshot.Edge edge : valid) {
            appendEdge(svg, edge, byId.get(edge.to()), laneOf, rowOf, firstRowOfLane, rowCenter, laneW, column);
        }
        svg.append('\n');
        for (RunReportSnapshot.Step step : steps) {
            appendBlock(svg, step, numberOf.get(step.id()), laneX(column[laneOf.get(step.id())], laneW),
                    rowCenter[rowOf.get(step.id())], radiusOf.get(step.id()));
        }
        return svg.append("</svg>").toString();
    }

    private static void appendEdge(StringBuilder svg, RunReportSnapshot.Edge edge, RunReportSnapshot.Step child,
            Map<Long, Integer> laneOf, Map<Long, Integer> rowOf, Map<Integer, Integer> firstRowOfLane, double[] rowCenter,
            int laneW, int[] column) {
        int fromLane = laneOf.get(edge.from());
        int toLane = laneOf.get(edge.to());
        int fromRow = rowOf.get(edge.from());
        int toRow = rowOf.get(edge.to());
        double x1 = laneX(column[fromLane], laneW);
        double y1 = rowCenter[fromRow];
        double x2 = laneX(column[toLane], laneW);
        double y2 = rowCenter[toRow];
        boolean fork = toRow == firstRowOfLane.get(toLane);
        StringBuilder d = new StringBuilder("M").append(n(x1)).append(',').append(n(y1));
        String color;
        if (fromLane == toLane) {
            d.append(" L").append(n(x2)).append(',').append(n(y2));
            color = LANE_COLORS[toLane % LANE_COLORS.length];
        } else if (toRow - fromRow <= 1) {
            d.append(bend(x1, y1, x2, y2));
            color = LANE_COLORS[(fork ? toLane : fromLane) % LANE_COLORS.length];
        } else if (fork) {
            // leave the parent at once, then run down the child's own lane
            d.append(bend(x1, y1, x2, rowCenter[fromRow + 1])).append(" L").append(n(x2)).append(',').append(n(y2));
            color = LANE_COLORS[toLane % LANE_COLORS.length];
        } else {
            // run down the parent's lane, bend into the child's lane in the last gap
            d.append(" L").append(n(x1)).append(',').append(n(rowCenter[toRow - 1])).append(bend(x1, rowCenter[toRow - 1], x2, y2));
            color = LANE_COLORS[fromLane % LANE_COLORS.length];
        }
        svg.append("<path d=\"").append(d).append("\" fill=\"none\" stroke=\"").append(color)
                .append("\" stroke-width=\"3\" stroke-linecap=\"round\" stroke-opacity=\"0.85\"")
                .append(child.status() == RunStatus.PENDING ? " stroke-dasharray=\"2 5\"" : "").append("/>");
    }

    private static String bend(double x1, double y1, double x2, double y2) {
        double mid = (y1 + y2) / 2;
        return " C" + n(x1) + "," + n(mid) + " " + n(x2) + "," + n(mid) + " " + n(x2) + "," + n(y2);
    }

    private static void appendBlock(StringBuilder svg, RunReportSnapshot.Step step, int number, double cx, double cy, double r) {
        String fill = ReportText.statusColor(step.status());
        boolean notRun = step.status() == null || step.status() == RunStatus.PENDING;
        String name = step.name() == null ? "(без имени)" : step.name();
        double fontSize = Math.max(10.5, Math.min(18, r * 0.6));
        double textY = cy + fontSize * 0.35;
        String shape;
        ScenarioStepType type = step.type();
        if (type == ScenarioStepType.QUEUE) {
            shape = "<circle cx=\"" + n(cx) + "\" cy=\"" + n(cy) + "\" r=\"" + n(r) + "\" fill=\"" + fill
                    + "\" stroke=\"#fff\" stroke-width=\"2\"/>";
        } else if (type == ScenarioStepType.QUEUE_CHECK) {
            shape = "<rect x=\"" + n(cx - r) + "\" y=\"" + n(cy - r) + "\" width=\"" + n(2 * r) + "\" height=\"" + n(2 * r)
                    + "\" rx=\"3\" fill=\"" + fill + "\" stroke=\"#fff\" stroke-width=\"2\"/>";
        } else {
            double half = r * TRIANGLE_FACTOR;
            shape = "<path d=\"M" + n(cx - half) + "," + n(cy - r) + " L" + n(cx + half) + "," + n(cy - r) + " L" + n(cx) + ","
                    + n(cy + r * 1.1) + " Z\" fill=\"" + fill + "\" stroke=\"#fff\" stroke-width=\"2\" stroke-linejoin=\"round\"/>";
            textY = cy - r * 0.3 + fontSize * 0.35;
        }
        svg.append("<a href=\"#step-").append(number).append("\"><title>").append(number).append(". ").append(esc(name))
                .append(" - ").append(esc(ReportText.statusLabel(step.status()))).append(", ")
                .append(esc(ReportText.duration(step.durationSeconds()))).append("</title>").append(shape)
                .append("<text x=\"").append(n(cx)).append("\" y=\"").append(n(textY)).append("\" font-size=\"").append(n(fontSize))
                .append("\" font-weight=\"700\" text-anchor=\"middle\" fill=\"").append(notRun ? "#323f4b" : "#fff")
                .append("\" pointer-events=\"none\">").append(number).append("</text></a>\n");
    }

    private static boolean ran(RunReportSnapshot.Step step) {
        return step.status() != RunStatus.PENDING && step.durationSeconds() != null;
    }

    /**
     * Logarithmic between the quickest and the slowest executed step: the quickest is {@link #MIN_R},
     * the slowest {@link #MAX_R}, and the steps in between are spread by how many times slower they
     * are than the quickest. A linear scale squeezed everything but one long outlier into the
     * smallest size (10 s, 30 s and 60 s next to a 5-minute step looked identical). The scale is
     * relative to this run; exact times are in the tooltip, the table and the timeline. If every
     * step took the same time, all get the middle size. A step that did not run is the smallest block.
     */
    private static double radius(RunReportSnapshot.Step step, long minDuration, long maxDuration) {
        if (!ran(step)) {
            return MIN_R;
        }
        double low = Math.log(Math.max(1, minDuration));
        double high = Math.log(Math.max(1, maxDuration));
        if (high <= low) {
            return (MIN_R + MAX_R) / 2;
        }
        double position = (Math.log(Math.max(1, step.durationSeconds())) - low) / (high - low);
        return MIN_R + (MAX_R - MIN_R) * position;
    }

    private static double extent(RunReportSnapshot.Step step, double r) {
        return step.type() == ScenarioStepType.QUEUE || step.type() == ScenarioStepType.QUEUE_CHECK ? r : r * 1.1;
    }

    /** Lane 0 (the main chain, which holds the root and usually the join) goes to the middle, the other
     * lanes alternate to its right and left, so a fork or a merge fans out symmetrically. */
    static int[] columnOfLane(int lanes) {
        int[] column = new int[lanes];
        int middle = (lanes - 1) / 2;
        for (int lane = 0; lane < lanes; lane++) {
            int offset = (lane + 1) / 2;
            column[lane] = lane == 0 ? middle : lane % 2 == 1 ? middle + offset : middle - offset;
        }
        return column;
    }

    private static double laneX(int lane, int laneW) {
        return PAD + lane * laneW + laneW / 2.0;
    }

    private static String n(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private static String esc(String text) {
        return text == null ? "" : HtmlUtils.htmlEscape(text, "UTF-8");
    }
}
