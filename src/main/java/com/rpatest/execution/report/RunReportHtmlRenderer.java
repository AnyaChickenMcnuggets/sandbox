package com.rpatest.execution.report;

import com.rpatest.execution.domain.RunStatus;
import com.rpatest.scenario.domain.ScenarioStepType;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.web.util.HtmlUtils;

/**
 * Renders the frozen {@link RunReportSnapshot} as one self-contained HTML page: inline CSS, inline
 * SVG, no scripts - it is meant to be read in a browser and saved as PDF through "Print". Every
 * value that came from users or the orchestrator (step names, errors, natural keys) is escaped.
 */
public final class RunReportHtmlRenderer {

    private static final String CSS = """
            *{box-sizing:border-box}
            body{margin:0;background:#fff;color:#1f2933;font:14px/1.45 -apple-system,"Segoe UI",Roboto,Arial,sans-serif}
            main{max-width:1100px;margin:0 auto;padding:24px 20px 48px}
            h1{font-size:24px;margin:0 0 4px}h2{font-size:18px;margin:32px 0 10px;border-bottom:1px solid #d9e2ec;padding-bottom:6px}
            h3{font-size:15px;margin:0 0 6px}
            .muted{color:#7b8794}.verdict{display:inline-block;padding:6px 14px;border-radius:6px;color:#fff;font-weight:700;letter-spacing:.04em;margin:10px 0}
            .ok{background:#2f9e44}.fail{background:#e03131}.warn{background:#f08c00}.neutral{background:#7b8794}
            table{border-collapse:collapse;width:100%;margin:6px 0 12px}th,td{border:1px solid #d9e2ec;padding:6px 9px;text-align:left;vertical-align:top}
            th{background:#f0f4f8;font-weight:600}td.num{text-align:right;white-space:nowrap}
            dl{display:grid;grid-template-columns:200px 1fr;gap:4px 14px;margin:8px 0}dt{color:#52606d}dd{margin:0;font-weight:600}
            .card{border:1px solid #d9e2ec;border-radius:8px;padding:12px 14px;margin:0 0 14px}
            .badge{display:inline-block;padding:1px 9px;border-radius:10px;color:#fff;font-size:12px;font-weight:600;vertical-align:middle}
            .cause{border-left:4px solid #e03131;background:#fff5f5;padding:10px 14px;white-space:pre-wrap;word-break:break-word}
            code{background:#f0f4f8;padding:1px 5px;border-radius:3px}
            tr:target td{background:#fff8c5}.graph{border:1px solid #d9e2ec;border-radius:8px;padding:8px;margin:6px 0 12px}
            .legend{margin:0 0 6px}.legend span{margin-right:16px;font-size:12px;color:#52606d}
            .legend i{display:inline-block;width:11px;height:11px;border-radius:3px;margin-right:5px;vertical-align:-1px}
            details{margin-top:4px}summary{cursor:pointer;color:#52606d;font-size:12px}
            .tech{margin-top:4px;padding:6px 8px;background:#f0f4f8;border-radius:4px;font:12px/1.4 Consolas,monospace;white-space:pre-wrap;word-break:break-word}
            @media print{main{padding:0}.card,tr,svg{break-inside:avoid}h2{break-after:avoid}}
            """;

    private RunReportHtmlRenderer() {
    }

    public static String render(RunReportSnapshot report) {
        StringBuilder html = new StringBuilder(16_384);
        String title = "Отчёт по запуску №" + report.runId() + " - " + nullToDash(report.scenarioName());
        html.append("<!DOCTYPE html>\n<html lang=\"ru\"><head><meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
                .append("<title>").append(esc(title)).append("</title><style>").append(CSS).append("</style></head><body><main>\n");

        header(html, report);
        html.append("<h2>Ход выполнения</h2>\n")
                .append("<p class=\"muted\">Выполнение идёт сверху вниз. Каждый блок - один шаг; число внутри блока - номер шага ")
                .append("в таблице «Шаги» ниже, по нажатию на блок открывается его строка. Параллельные ветки - отдельные ")
                .append("цветные линии: где линия делится, начинаются параллельные ветки, где сходится - шаг дожидается всех веток. ")
                .append("Форма блока - тип шага, цвет - результат, размер - длительность относительно самого быстрого и самого долгого шага этого прогона (самый долгий - самый большой блок).")
                .append("Названия шагов видны в таблице и во всплывающей подсказке.</p>\n")
                .append(StepGraphDiagram.legend()).append(legend())
                .append("<div class=\"graph\">").append(StepGraphDiagram.render(report.steps(), report.edges())).append("</div>\n")
                .append("<h2>Хронология</h2>\n")
                .append("<p class=\"muted\">Когда каждый шаг начался и сколько длился, по общей шкале времени прогона.</p>\n")
                .append(GanttDiagram.render(report)).append('\n');
        failureCause(html, report);
        stepsTable(html, report);
        queueChecks(html, report);
        jobs(html, report);
        queues(html, report);

        html.append("<p class=\"muted\" style=\"margin-top:32px\">Отчёт сформирован ")
                .append(esc(ReportText.dateTime(report.generatedAt()))).append("</p>\n</main></body></html>\n");
        return html.toString();
    }

    private static void header(StringBuilder html, RunReportSnapshot report) {
        html.append("<h1>Отчёт о тестировании</h1>\n<div class=\"verdict ").append(verdictClass(report.status())).append("\">")
                .append(esc(ReportText.verdictLabel(report.status()))).append("</div>\n<dl>\n");
        row(html, "Сценарий", nullToDash(report.scenarioName()));
        row(html, "Запуск", "№" + report.runId());
        row(html, "Запустил", nullToDash(report.triggeredBy()));
        row(html, "Начало", ReportText.dateTime(report.startedAt()));
        row(html, "Окончание", ReportText.dateTime(report.finishedAt()));
        row(html, "Длительность", ReportText.duration(report.durationSeconds()));
        row(html, "Шаги", ReportText.stepsSummary(report));
        if (report.startStepId() != null) {
            row(html, "Запуск с шага", "не с начала сценария (шаги до точки старта не выполнялись)");
        }
        html.append("</dl>\n");
    }

    private static void failureCause(StringBuilder html, RunReportSnapshot report) {
        ReportText.firstFailed(report).ifPresent(step -> {
            html.append("<h2>Причина ошибки</h2>\n<p>Первым завершился с ошибкой шаг <b>«")
                    .append(esc(step.name())).append("»</b> (").append(esc(ReportText.stepTypeLabel(step.type())))
                    .append("). Последующие ошибки могут быть его следствием.</p>\n<div class=\"cause\">");
            StepComment.Comment comment = StepComment.describe(report, step);
            html.append(esc(comment.text())).append(technicalDetails(comment)).append("</div>\n");
        });
    }

    private static void stepsTable(StringBuilder html, RunReportSnapshot report) {
        html.append("<h2>Шаги</h2>\n<table><tr><th>№</th><th>Шаг</th><th>Тип</th><th>Статус</th><th>Начало</th>")
                .append("<th>Длительность</th><th>Комментарий</th></tr>\n");
        int index = 1;
        for (RunReportSnapshot.Step step : report.steps()) {
            StepComment.Comment comment = StepComment.describe(report, step);
            html.append("<tr id=\"step-").append(index).append("\"><td class=\"num\">").append(index++).append("</td><td>").append(esc(step.name()))
                    .append("</td><td>").append(esc(ReportText.stepTypeLabel(step.type())))
                    .append("</td><td>").append(statusBadge(step.status()))
                    .append("</td><td class=\"num\">").append(esc(ReportText.dateTime(step.startedAt())))
                    .append("</td><td class=\"num\">").append(esc(ReportText.duration(step.durationSeconds())))
                    .append("</td><td>").append(esc(comment.text())).append(technicalDetails(comment)).append("</td></tr>\n");
        }
        html.append("</table>\n");
    }

    private static void queueChecks(StringBuilder html, RunReportSnapshot report) {
        List<RunReportSnapshot.Step> checks = stepsOfType(report, ScenarioStepType.QUEUE_CHECK);
        if (checks.isEmpty()) {
            return;
        }
        html.append("<h2>Проверки очередей</h2>\n");
        for (RunReportSnapshot.Step step : checks) {
            Map<String, Object> result = step.result();
            html.append("<div class=\"card\"><h3>").append(esc(step.name())).append(' ');
            if (result == null) {
                StepComment.Comment comment = StepComment.describe(report, step);
                html.append("<span class=\"badge neutral\">Нет данных</span></h3>\n<p class=\"muted\">")
                        .append(esc(comment.text())).append(technicalDetails(comment)).append("</p></div>\n");
                continue;
            }
            boolean passed = Boolean.TRUE.equals(result.get("passed"));
            html.append("<span class=\"badge ").append(passed ? "ok" : "fail").append("\">")
                    .append(passed ? "Пройдена" : "Не пройдена").append("</span></h3>\n");
            html.append("<p>Очередь: <code>").append(esc(String.valueOf(result.get("queueName")))).append("</code></p>\n");
            expectationTable(html, result);
            transactionsTable(html, result);
            html.append("</div>\n");
        }
    }

    private static void expectationTable(StringBuilder html, Map<String, Object> result) {
        Map<String, Object> expected = asMap(result.get("expected"));
        Map<String, Object> actual = asMap(result.get("actual"));
        Set<String> statuses = new LinkedHashSet<>(expected.keySet());
        statuses.addAll(actual.keySet());
        html.append("<table><tr><th>Статус транзакций</th><th>Ожидалось (не менее)</th><th>Получено</th><th>Результат</th></tr>\n");
        for (String status : statuses) {
            Long want = expected.containsKey(status) ? number(expected.get(status)) : null;
            long got = number(actual.get(status));
            expectationRow(html, ReportText.queueStatusLabel(status), want, got);
        }
        Object minTotal = result.get("minTotalCount");
        if (minTotal != null) {
            expectationRow(html, "Всего транзакций", number(minTotal), number(result.get("actualTotal")));
        } else if (statuses.isEmpty()) {
            html.append("<tr><td colspan=\"4\" class=\"muted\">Конкретных ожиданий по количеству не задано. Получено транзакций: ")
                    .append(number(result.get("actualTotal"))).append("</td></tr>\n");
        }
        html.append("</table>\n");
    }

    private static void expectationRow(StringBuilder html, String label, Long want, long got) {
        boolean satisfied = want == null || got >= want;
        html.append("<tr><td>").append(esc(label)).append("</td><td class=\"num\">").append(want == null ? "-" : want)
                .append("</td><td class=\"num\">").append(got).append("</td><td>")
                .append(want == null ? "-" : satisfied ? "&#10003; выполнено" : "&#10007; не хватает " + (want - got))
                .append("</td></tr>\n");
    }

    private static void transactionsTable(StringBuilder html, Map<String, Object> result) {
        List<Object> transactions = asList(result.get("transactions"));
        if (transactions.isEmpty()) {
            return;
        }
        long total = number(result.get("transactionsTotal"));
        html.append("<details open><summary>Транзакции в очереди");
        if (total > transactions.size()) {
            html.append(" (показаны первые ").append(transactions.size()).append(" из ").append(total).append(')');
        }
        html.append("</summary><table><tr><th>naturalKey</th><th>Статус</th><th>Повторов</th></tr>\n");
        for (Object item : transactions) {
            Map<String, Object> row = asMap(item);
            html.append("<tr><td>").append(esc(String.valueOf(row.get("naturalKey")))).append("</td><td>")
                    .append(esc(ReportText.queueStatusLabel(String.valueOf(row.get("status"))))).append("</td><td class=\"num\">")
                    .append(number(row.get("retray"))).append("</td></tr>\n");
        }
        html.append("</table></details>\n");
    }

    private static void jobs(StringBuilder html, RunReportSnapshot report) {
        List<RunReportSnapshot.Step> jobs = stepsOfType(report, ScenarioStepType.JOB).stream()
                .filter(s -> s.result() != null).toList();
        if (jobs.isEmpty()) {
            return;
        }
        html.append("<h2>Задания</h2>\n<table><tr><th>Шаг</th><th>Задание</th><th>Проект</th><th>Робот</th>")
                .append("<th>Начало на роботе</th><th>Завершено</th><th>Итог</th></tr>\n");
        for (RunReportSnapshot.Step step : jobs) {
            Map<String, Object> r = step.result();
            boolean success = Boolean.TRUE.equals(r.get("success"));
            html.append("<tr><td>").append(esc(step.name())).append("</td><td>").append(esc(text(r.get("assignmentName"))))
                    .append("</td><td>").append(esc(text(r.get("projectName")))).append("</td><td>").append(esc(text(r.get("robotName"))))
                    .append("</td><td class=\"num\">").append(esc(ReportText.dateTime(text(r.get("robotStartedAt")))))
                    .append("</td><td class=\"num\">").append(esc(ReportText.dateTime(text(r.get("completedAt")))))
                    .append("</td><td><span class=\"badge ").append(success ? "ok" : "fail").append("\">")
                    .append(success ? "Успешно" : "Ошибка").append("</span></td></tr>\n");
        }
        html.append("</table>\n");
    }

    private static void queues(StringBuilder html, RunReportSnapshot report) {
        List<RunReportSnapshot.Step> queues = stepsOfType(report, ScenarioStepType.QUEUE).stream()
                .filter(s -> s.result() != null).toList();
        if (queues.isEmpty()) {
            return;
        }
        html.append("<h2>Очереди</h2>\n<table><tr><th>Шаг</th><th>Очередь</th><th>Происхождение</th><th>Добавлено транзакций</th></tr>\n");
        for (RunReportSnapshot.Step step : queues) {
            Map<String, Object> r = step.result();
            html.append("<tr><td>").append(esc(step.name())).append("</td><td><code>").append(esc(text(r.get("queueName"))))
                    .append("</code></td><td>")
                    .append(Boolean.TRUE.equals(r.get("created")) ? "создана этим запуском" : "использована существующая")
                    .append("</td><td class=\"num\">").append(number(r.get("transactionsAdded"))).append("</td></tr>\n");
        }
        html.append("</table>\n");
    }

    private static String legend() {
        StringBuilder legend = new StringBuilder("<p class=\"legend\">");
        for (RunStatus status : List.of(RunStatus.SUCCEEDED, RunStatus.FAILED, RunStatus.STOPPED, RunStatus.RUNNING, RunStatus.PENDING)) {
            legend.append("<span><i style=\"background:").append(ReportText.statusColor(status)).append("\"></i>")
                    .append(esc(ReportText.statusLabel(status))).append("</span>");
        }
        return legend.append("</p>\n").toString();
    }

    private static String technicalDetails(StepComment.Comment comment) {
        return comment.technical() == null || comment.technical().isBlank() ? ""
                : "<details><summary>Технические детали</summary><div class=\"tech\">" + esc(comment.technical()) + "</div></details>";
    }

    private static List<RunReportSnapshot.Step> stepsOfType(RunReportSnapshot report, ScenarioStepType type) {
        return report.steps().stream().filter(s -> s.type() == type).toList();
    }

    private static void row(StringBuilder html, String label, String value) {
        html.append("<dt>").append(esc(label)).append("</dt><dd>").append(esc(value)).append("</dd>\n");
    }

    private static String statusBadge(RunStatus status) {
        return "<span class=\"badge " + switch (status == null ? RunStatus.PENDING : status) {
            case SUCCEEDED -> "ok";
            case FAILED -> "fail";
            case STOPPED -> "warn";
            case RUNNING, PENDING -> "neutral";
        } + "\">" + esc(ReportText.statusLabel(status)) + "</span>";
    }

    private static String verdictClass(RunStatus status) {
        if (status == null) {
            return "neutral";
        }
        return switch (status) {
            case SUCCEEDED -> "ok";
            case FAILED -> "fail";
            case STOPPED -> "warn";
            case RUNNING, PENDING -> "neutral";
        };
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object value) {
        return value instanceof List<?> list ? new ArrayList<>((List<Object>) list) : List.of();
    }

    private static long number(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }

    private static String nullToDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private static String esc(String value) {
        return value == null ? "" : HtmlUtils.htmlEscape(value, "UTF-8");
    }
}
