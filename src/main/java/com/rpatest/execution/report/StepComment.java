package com.rpatest.execution.report;

import com.rpatest.execution.domain.RunStatus;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The "Comment" of a step in the report and the mail: what the engine stored for the step (the error
 * for a failed one, otherwise the last progress text), translated into readable Russian by
 * {@link ReportMessages} - nothing is summarised away. The original text is kept as
 * {@link Comment#technical()} whenever the translation changed it. A step that did not run has no
 * text of its own, so the reason is worked out from the graph.
 */
public final class StepComment {

    private StepComment() {
    }

    /** @param text      the comment, in Russian
     *  @param technical the original (engine) text, or {@code null} when it is the same as {@code text} */
    public record Comment(String text, String technical) {
    }

    public static Comment describe(RunReportSnapshot report, RunReportSnapshot.Step step) {
        RunStatus status = step.status() == null ? RunStatus.PENDING : step.status();
        if (status == RunStatus.PENDING) {
            return new Comment(notRunReason(report, step), null);
        }
        String raw = step.errorMessage() != null && !step.errorMessage().isBlank() ? step.errorMessage() : step.detail();
        if (raw == null || raw.isBlank()) {
            return new Comment(switch (status) {
                case SUCCEEDED -> "Выполнен успешно.";
                case FAILED -> "Шаг завершился с ошибкой, текст ошибки не сохранён.";
                case STOPPED -> "Остановлен.";
                default -> "Шаг выполнялся в момент формирования отчёта.";
            }, null);
        }
        String translated = ReportMessages.translate(raw);
        return new Comment(translated, ReportMessages.wasTranslated(raw) ? raw : null);
    }

    /**
     * Why a step stayed {@code PENDING}. Order matters: a step that is an ancestor of an executed
     * one was before the start point of a run started from the middle of the scenario (a normal run
     * never executes a step whose parent did not run); otherwise it is waiting behind a failed
     * ancestor; otherwise the run was stopped, or it was not part of a run from the middle.
     */
    private static String notRunReason(RunReportSnapshot report, RunReportSnapshot.Step step) {
        Map<Long, List<Long>> parents = new HashMap<>();
        Map<Long, List<Long>> children = new HashMap<>();
        Map<Long, RunReportSnapshot.Step> byId = new HashMap<>();
        report.steps().forEach(s -> byId.put(s.id(), s));
        for (RunReportSnapshot.Edge edge : report.edges()) {
            if (byId.containsKey(edge.from()) && byId.containsKey(edge.to())) {
                parents.computeIfAbsent(edge.to(), k -> new ArrayList<>()).add(edge.from());
                children.computeIfAbsent(edge.from(), k -> new ArrayList<>()).add(edge.to());
            }
        }
        if (reachable(step.id(), children, byId, s -> s.status() != RunStatus.PENDING) != null) {
            return "Не выполнялся: прогон начат с середины сценария, этот шаг расположен до точки старта.";
        }
        RunReportSnapshot.Step failed = reachable(step.id(), parents, byId, s -> s.status() == RunStatus.FAILED);
        if (failed != null) {
            return "Не выполнялся: шаг «" + failed.name() + "» (№" + (report.steps().indexOf(failed) + 1)
                    + "), от которого он зависит, завершился с ошибкой.";
        }
        if (report.status() == RunStatus.STOPPED) {
            return "Не выполнялся: прогон был остановлен.";
        }
        if (report.startStepId() != null) {
            return "Не выполнялся: шаг не входит в запуск с середины сценария.";
        }
        return "Не выполнялся.";
    }

    /** Breadth-first search from {@code from} (exclusive) along {@code next}: the nearest step matching {@code wanted}. */
    private static RunReportSnapshot.Step reachable(Long from, Map<Long, List<Long>> next,
            Map<Long, RunReportSnapshot.Step> byId, Predicate<RunReportSnapshot.Step> wanted) {
        Set<Long> seen = new HashSet<>(List.of(from));
        Deque<Long> queue = new ArrayDeque<>(next.getOrDefault(from, List.of()));
        while (!queue.isEmpty()) {
            Long id = queue.poll();
            if (!seen.add(id)) {
                continue;
            }
            RunReportSnapshot.Step candidate = byId.get(id);
            if (wanted.test(candidate)) {
                return candidate;
            }
            queue.addAll(next.getOrDefault(id, List.of()));
        }
        return null;
    }
}
