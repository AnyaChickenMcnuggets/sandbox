package com.rpatest.execution.report;

import com.rpatest.execution.domain.RunStatus;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.web.util.HtmlUtils;

/**
 * Sankey-style flow diagram of the run: one node per step, one band per DAG edge, columns are the
 * longest-path depth from the roots, node height and band width are proportional to time. Built as
 * plain inline SVG on the server: the report must be self-contained (no scripts, no CDN), print to
 * PDF and open on a machine without internet access.
 *
 * <p>Widths: a step passes its duration on to its children (split evenly between them), so a band
 * is "time the flow spent in the parent" and a node is as tall as the larger of its own duration
 * and the flow in or out of it. Steps that did not run get the minimum duration (1 s), otherwise
 * they would vanish.
 */
final class SankeyDiagram {

    private static final int NODE_WIDTH = 14;
    private static final int COLUMN_GAP = 230;
    private static final int PADDING_X = 10;
    private static final int PADDING_Y = 24;
    private static final int LABEL_ROOM = 215;
    private static final int MAX_INNER_HEIGHT = 380;
    private static final int NODE_GAP = 22;
    private static final int MIN_NODE_HEIGHT = 18;
    private static final int MAX_LABEL_CHARS = 30;

    private SankeyDiagram() {
    }

    static String render(List<RunReportSnapshot.Step> steps, List<RunReportSnapshot.Edge> edges) {
        if (steps.isEmpty()) {
            return "<p class=\"muted\">В прогоне нет шагов.</p>";
        }
        Map<Long, RunReportSnapshot.Step> byId = new LinkedHashMap<>();
        steps.forEach(s -> byId.put(s.id(), s));

        Map<Long, List<Long>> outgoing = new HashMap<>();
        Map<Long, List<Long>> incoming = new HashMap<>();
        Set<List<Long>> seen = new LinkedHashSet<>();
        for (RunReportSnapshot.Edge edge : edges) {
            if (byId.containsKey(edge.from()) && byId.containsKey(edge.to()) && seen.add(List.of(edge.from(), edge.to()))) {
                outgoing.computeIfAbsent(edge.from(), k -> new ArrayList<>()).add(edge.to());
                incoming.computeIfAbsent(edge.to(), k -> new ArrayList<>()).add(edge.from());
            }
        }

        Map<Long, Integer> layerOf = computeLayers(byId, seen);
        Map<Long, Double> duration = new HashMap<>();
        byId.values().forEach(s -> duration.put(s.id(), (double) Math.max(1, s.durationSeconds() == null ? 0 : s.durationSeconds())));

        Map<Long, Double> valueOf = new HashMap<>();
        for (Long id : byId.keySet()) {
            double in = incoming.getOrDefault(id, List.of()).stream().mapToDouble(p -> linkValue(p, outgoing, duration)).sum();
            double out = outgoing.getOrDefault(id, List.of()).isEmpty() ? 0 : duration.get(id);
            valueOf.put(id, Math.max(duration.get(id), Math.max(in, out)));
        }

        Map<Integer, List<Long>> columns = new TreeMap<>();
        byId.keySet().forEach(id -> columns.computeIfAbsent(layerOf.get(id), k -> new ArrayList<>()).add(id));

        double scale = Double.MAX_VALUE;
        for (List<Long> column : columns.values()) {
            double available = MAX_INNER_HEIGHT - (double) NODE_GAP * (column.size() - 1);
            double sum = column.stream().mapToDouble(valueOf::get).sum();
            scale = Math.min(scale, Math.max(available, MIN_NODE_HEIGHT * column.size()) / sum);
        }

        Map<Long, Double> heightOf = new HashMap<>();
        for (Long id : byId.keySet()) {
            heightOf.put(id, Math.max(MIN_NODE_HEIGHT, valueOf.get(id) * scale));
        }
        double innerHeight = 0;
        for (List<Long> column : columns.values()) {
            innerHeight = Math.max(innerHeight, column.stream().mapToDouble(heightOf::get).sum() + (double) NODE_GAP * (column.size() - 1));
        }

        Map<Long, Double> yOf = new HashMap<>();
        for (List<Long> column : columns.values()) {
            double columnHeight = column.stream().mapToDouble(heightOf::get).sum() + (double) NODE_GAP * (column.size() - 1);
            double y = PADDING_Y + (innerHeight - columnHeight) / 2;
            for (Long id : column) {
                yOf.put(id, y);
                y += heightOf.get(id) + NODE_GAP;
            }
        }

        int maxLayer = columns.keySet().stream().mapToInt(Integer::intValue).max().orElse(0);
        int width = PADDING_X + maxLayer * COLUMN_GAP + NODE_WIDTH + LABEL_ROOM;
        int height = (int) Math.ceil(innerHeight + 2 * PADDING_Y);

        StringBuilder svg = new StringBuilder();
        svg.append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 ").append(width).append(' ').append(height)
                .append("\" width=\"100%\" style=\"max-width:").append(width).append("px\" role=\"img\" ")
                .append("aria-label=\"Диаграмма последовательности шагов\">\n");

        appendLinks(svg, byId, outgoing, incoming, layerOf, duration, valueOf, heightOf, yOf);
        for (Long id : byId.keySet()) {
            appendNode(svg, byId.get(id), PADDING_X + layerOf.get(id) * COLUMN_GAP, yOf.get(id), heightOf.get(id));
        }
        return svg.append("</svg>").toString();
    }

    private static Map<Long, Integer> computeLayers(Map<Long, RunReportSnapshot.Step> byId, Set<List<Long>> edges) {
        Map<Long, Integer> layer = new HashMap<>();
        byId.keySet().forEach(id -> layer.put(id, 0));
        // Relaxation bounded by the node count: a cycle (the scenario validator forbids it) would
        // otherwise loop forever.
        for (int i = 0; i < byId.size(); i++) {
            boolean changed = false;
            for (List<Long> edge : edges) {
                int candidate = layer.get(edge.get(0)) + 1;
                if (candidate > layer.get(edge.get(1))) {
                    layer.put(edge.get(1), candidate);
                    changed = true;
                }
            }
            if (!changed) {
                break;
            }
        }
        return layer;
    }

    private static double linkValue(Long from, Map<Long, List<Long>> outgoing, Map<Long, Double> duration) {
        return duration.get(from) / Math.max(1, outgoing.getOrDefault(from, List.of()).size());
    }

    private static void appendLinks(
            StringBuilder svg,
            Map<Long, RunReportSnapshot.Step> byId,
            Map<Long, List<Long>> outgoing,
            Map<Long, List<Long>> incoming,
            Map<Long, Integer> layerOf,
            Map<Long, Double> duration,
            Map<Long, Double> valueOf,
            Map<Long, Double> heightOf,
            Map<Long, Double> yOf) {
        Map<List<Long>, Double> sourceOffset = new HashMap<>();
        Map<List<Long>, Double> targetOffset = new HashMap<>();
        Map<List<Long>, Double> thickness = new HashMap<>();

        for (Long id : byId.keySet()) {
            double cursor = 0;
            List<Long> targets = new ArrayList<>(outgoing.getOrDefault(id, List.of()));
            targets.sort(Comparator.comparing(yOf::get));
            for (Long target : targets) {
                double t = linkValue(id, outgoing, duration)
                        * Math.min(heightOf.get(id) / valueOf.get(id), heightOf.get(target) / valueOf.get(target));
                thickness.put(List.of(id, target), t);
                sourceOffset.put(List.of(id, target), cursor);
                cursor += t;
            }
        }
        for (Long id : byId.keySet()) {
            double cursor = 0;
            List<Long> sources = new ArrayList<>(incoming.getOrDefault(id, List.of()));
            sources.sort(Comparator.comparing(yOf::get));
            for (Long source : sources) {
                targetOffset.put(List.of(source, id), cursor);
                cursor += thickness.get(List.of(source, id));
            }
        }

        for (Map.Entry<List<Long>, Double> entry : thickness.entrySet()) {
            Long from = entry.getKey().get(0);
            Long to = entry.getKey().get(1);
            double t = Math.max(1.5, entry.getValue());
            double x1 = PADDING_X + layerOf.get(from) * COLUMN_GAP + NODE_WIDTH;
            double x2 = PADDING_X + layerOf.get(to) * COLUMN_GAP;
            double y1 = yOf.get(from) + sourceOffset.get(entry.getKey());
            double y2 = yOf.get(to) + targetOffset.get(entry.getKey());
            double mid = (x1 + x2) / 2;
            RunReportSnapshot.Step target = byId.get(to);
            svg.append("<path d=\"M").append(n(x1)).append(',').append(n(y1))
                    .append(" C").append(n(mid)).append(',').append(n(y1)).append(' ').append(n(mid)).append(',').append(n(y2))
                    .append(' ').append(n(x2)).append(',').append(n(y2))
                    .append(" L").append(n(x2)).append(',').append(n(y2 + t))
                    .append(" C").append(n(mid)).append(',').append(n(y2 + t)).append(' ').append(n(mid)).append(',').append(n(y1 + t))
                    .append(' ').append(n(x1)).append(',').append(n(y1 + t)).append(" Z\" fill=\"")
                    .append(color(target.status())).append("\" fill-opacity=\"0.32\"><title>")
                    .append(esc(byId.get(from).name())).append(" → ").append(esc(target.name())).append("</title></path>\n");
        }
    }

    private static void appendNode(StringBuilder svg, RunReportSnapshot.Step step, double x, double y, double height) {
        String label = step.name() == null ? "(без имени)" : step.name();
        String shown = label.length() > MAX_LABEL_CHARS ? label.substring(0, MAX_LABEL_CHARS - 1) + "…" : label;
        double textX = x + NODE_WIDTH + 6;
        double textY = y + height / 2;
        svg.append("<rect x=\"").append(n(x)).append("\" y=\"").append(n(y)).append("\" width=\"").append(NODE_WIDTH)
                .append("\" height=\"").append(n(height)).append("\" rx=\"2\" fill=\"").append(color(step.status()))
                .append("\"><title>").append(esc(label)).append(" - ").append(esc(ReportText.statusLabel(step.status())))
                .append(", ").append(esc(ReportText.duration(step.durationSeconds()))).append("</title></rect>\n");
        svg.append("<text x=\"").append(n(textX)).append("\" y=\"").append(n(textY - 3))
                .append("\" font-size=\"12\" font-weight=\"600\" fill=\"#1f2933\" stroke=\"#fff\" stroke-width=\"3\" ")
                .append("paint-order=\"stroke\">").append(esc(shown)).append("</text>\n");
        svg.append("<text x=\"").append(n(textX)).append("\" y=\"").append(n(textY + 11))
                .append("\" font-size=\"11\" fill=\"#52606d\" stroke=\"#fff\" stroke-width=\"3\" paint-order=\"stroke\">")
                .append(esc(ReportText.statusLabel(step.status()))).append(" · ")
                .append(esc(ReportText.duration(step.durationSeconds()))).append("</text>\n");
    }

    static String color(RunStatus status) {
        if (status == null) {
            return "#9aa5b1";
        }
        return switch (status) {
            case SUCCEEDED -> "#2f9e44";
            case FAILED -> "#e03131";
            case RUNNING -> "#1971c2";
            case STOPPED -> "#f08c00";
            case PENDING -> "#9aa5b1";
        };
    }

    private static String n(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private static String esc(String text) {
        return text == null ? "" : HtmlUtils.htmlEscape(text);
    }
}
