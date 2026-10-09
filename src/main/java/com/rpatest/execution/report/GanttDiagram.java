package com.rpatest.execution.report;

import com.rpatest.execution.domain.RunStatus;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import org.springframework.web.util.HtmlUtils;

/**
 * Timeline: one row per step, a bar from its start to its end on a shared time axis. Shows what
 * ran in parallel, where the time went and where a gap (waiting) is - the structure is secondary.
 */
final class GanttDiagram {

    private static final int LABEL_W = 250;
    private static final int CHART_W = 640;
    private static final int ROW_H_ROOMY = 30;
    private static final int ROW_H_DENSE = 22;
    private static final int DENSE_FROM = 20;
    private static final int AXIS_H = 30;
    private static final int PAD = 12;
    private static final int TICKS = 5;
    private static final int MAX_LABEL_CHARS = 34;

    private GanttDiagram() {
    }

    static String render(RunReportSnapshot report) {
        List<RunReportSnapshot.Step> steps = report.steps();
        if (steps.isEmpty()) {
            return "<p class=\"muted\">В прогоне нет шагов.</p>";
        }
        OffsetDateTime origin = report.startedAt() != null ? report.startedAt() : steps.stream()
                .map(RunReportSnapshot.Step::startedAt).filter(t -> t != null).min(OffsetDateTime::compareTo).orElse(null);
        double total = Math.max(1, totalSeconds(report, origin));
        int width = PAD * 2 + LABEL_W + CHART_W + 70;
        int rowH = steps.size() > DENSE_FROM ? ROW_H_DENSE : ROW_H_ROOMY;
        int height = PAD * 2 + AXIS_H + steps.size() * rowH;

        StringBuilder svg = new StringBuilder();
        svg.append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 ").append(width).append(' ').append(height)
                .append("\" width=\"100%\" style=\"max-width:").append(width).append("px\" role=\"img\" ")
                .append("aria-label=\"Хронология выполнения шагов\">\n");
        double left = PAD + LABEL_W;
        for (int i = 0; i <= TICKS; i++) {
            double x = left + CHART_W * i / (double) TICKS;
            svg.append("<line x1=\"").append(n(x)).append("\" y1=\"").append(PAD + AXIS_H - 6).append("\" x2=\"").append(n(x))
                    .append("\" y2=\"").append(height - PAD).append("\" stroke=\"#e4e7eb\"/>");
            svg.append("<text x=\"").append(n(x)).append("\" y=\"").append(PAD + 12).append("\" font-size=\"11\" fill=\"#7b8794\" text-anchor=\"")
                    .append(i == 0 ? "start" : i == TICKS ? "end" : "middle").append("\">")
                    .append(esc(i == 0 ? "0 с" : ReportText.duration(Math.round(total * i / TICKS)))).append("</text>\n");
        }
        int row = 0;
        for (RunReportSnapshot.Step step : steps) {
            double y = PAD + AXIS_H + row * rowH;
            appendRow(svg, step, row + 1, y, rowH, left, origin, total);
            row++;
        }
        return svg.append("</svg>").toString();
    }

    private static void appendRow(StringBuilder svg, RunReportSnapshot.Step step, int number, double y, int rowH,
            double left, OffsetDateTime origin, double total) {
        String name = step.name() == null ? "(без имени)" : step.name();
        String shown = name.length() > MAX_LABEL_CHARS ? name.substring(0, MAX_LABEL_CHARS - 1) + "…" : name;
        boolean notRun = step.status() == RunStatus.PENDING || step.startedAt() == null || origin == null;
        String color = ReportText.statusColor(step.status());
        svg.append("<circle cx=\"").append(PAD + 6).append("\" cy=\"").append(n(y + rowH / 2.0)).append("\" r=\"5\" fill=\"")
                .append(color).append("\"/>");
        svg.append("<text x=\"").append(PAD + 18).append("\" y=\"").append(n(y + rowH / 2.0 + 4)).append("\" font-size=\"12\" fill=\"")
                .append(notRun ? "#9aa5b1" : "#1f2933").append("\"><title>").append(esc(name)).append("</title>")
                .append(number).append(". ").append(esc(shown)).append("</text>\n");
        if (notRun) {
            svg.append("<text x=\"").append(n(left + 6)).append("\" y=\"").append(n(y + rowH / 2.0 + 4))
                    .append("\" font-size=\"11\" font-style=\"italic\" fill=\"#9aa5b1\">не выполнялся</text>\n");
            return;
        }
        double offset = Math.max(0, Duration.between(origin, step.startedAt()).toMillis() / 1000.0);
        double length = step.durationSeconds() == null ? 0 : step.durationSeconds();
        double x = left + CHART_W * Math.min(offset, total) / total;
        double w = Math.max(3, CHART_W * length / total);
        w = Math.min(w, left + CHART_W - x + 1);
        svg.append("<rect x=\"").append(n(x)).append("\" y=\"").append(n(y + 6)).append("\" width=\"").append(n(w))
                .append("\" height=\"").append(rowH - 12).append("\" rx=\"3\" fill=\"").append(color).append("\"><title>")
                .append(esc(name)).append(": ").append(esc(ReportText.statusLabel(step.status()))).append(", ")
                .append(esc(ReportText.duration(step.durationSeconds()))).append("</title></rect>");
        svg.append("<text x=\"").append(n(x + w + 6)).append("\" y=\"").append(n(y + rowH / 2.0 + 4))
                .append("\" font-size=\"11\" fill=\"#52606d\">").append(esc(ReportText.duration(step.durationSeconds())))
                .append("</text>\n");
    }

    private static double totalSeconds(RunReportSnapshot report, OffsetDateTime origin) {
        if (report.durationSeconds() != null) {
            return report.durationSeconds();
        }
        if (origin == null) {
            return 1;
        }
        return report.steps().stream().map(RunReportSnapshot.Step::finishedAt).filter(t -> t != null)
                .mapToDouble(t -> Duration.between(origin, t).toMillis() / 1000.0).max().orElse(1);
    }

    private static String n(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private static String esc(String text) {
        return text == null ? "" : HtmlUtils.htmlEscape(text, "UTF-8");
    }
}
