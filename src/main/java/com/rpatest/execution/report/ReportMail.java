package com.rpatest.execution.report;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.util.HtmlUtils;

/**
 * Content of the "run finished" mail. Deliberately a short notice with the essentials and, if the
 * public base URL is configured, a link to the full report - not the report itself: mail clients
 * strip styles and SVG, and the report lives behind authentication anyway. The body is minimal
 * HTML (paragraphs and line breaks only); every inserted value is escaped.
 */
final class ReportMail {

    private static final DateTimeFormatter KEY_TIME = DateTimeFormatter.ofPattern("ddMMyyyyHHmmss");
    private static final int MAX_CAUSE_CHARS = 500;

    private ReportMail() {
    }

    /** {@code ID<ddMMyyyyHHmmss>_SandboxReport}, time of the run end (now if it has none). */
    static String naturalKey(RunReportSnapshot report) {
        OffsetDateTime time = report.finishedAt() != null ? report.finishedAt() : OffsetDateTime.now();
        return "ID" + KEY_TIME.format(time.atZoneSameInstant(ZoneId.systemDefault())) + "_SandboxReport";
    }

    static String subject(RunReportSnapshot report) {
        return "Отчет по запуску сценария \"" + report.scenarioName() + "\" №" + report.runId()
                + " за " + ReportText.date(report.finishedAt() != null ? report.finishedAt() : report.startedAt());
    }

    static String body(RunReportSnapshot report, String publicBaseUrl) {
        StringBuilder body = new StringBuilder();
        body.append("<p>Тестирование завершено.</p>")
                .append("<p><b>Сценарий:</b> ").append(esc(report.scenarioName())).append("<br>")
                .append("<b>Запуск:</b> №").append(report.runId()).append("<br>")
                .append("<b>Итог:</b> ").append(esc(ReportText.verdictLabel(report.status()))).append("<br>")
                .append("<b>Начало:</b> ").append(esc(ReportText.dateTime(report.startedAt()))).append("<br>")
                .append("<b>Окончание:</b> ").append(esc(ReportText.dateTime(report.finishedAt()))).append("<br>")
                .append("<b>Длительность:</b> ").append(esc(ReportText.duration(report.durationSeconds()))).append("<br>")
                .append("<b>Шаги:</b> ").append(esc(ReportText.stepsSummary(report))).append("</p>");
        ReportText.firstFailed(report).ifPresent(step -> body.append("<p><b>Ошибка в шаге «")
                .append(esc(step.name())).append("»:</b> ").append(esc(shorten(StepComment.describe(report, step).text()))).append("</p>"));
        if (publicBaseUrl != null && !publicBaseUrl.isBlank()) {
            String link = publicBaseUrl.replaceAll("/+$", "") + "/api/v1/runs/" + report.runId() + "/report";
            body.append("<p><a href=\"").append(esc(link)).append("\">Открыть полный отчет</a> ")
                    .append("(нужен вход в систему)</p>");
        }
        return body.toString();
    }

    /** {@code login@domain}; a login that already is an address, or an empty domain, leaves it as is. */
    static String recipient(String login, String mailDomain) {
        String domain = mailDomain == null ? "" : mailDomain.trim().replaceFirst("^@+", "");
        if (domain.isEmpty() || login.contains("@")) {
            return login;
        }
        return login + "@" + domain;
    }

    static Map<String, String> metadata(RunReportSnapshot report, String publicBaseUrl, String mailDomain) {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("Mail_To", recipient(report.triggeredBy().trim(), mailDomain));
        metadata.put("Mail_Subject", subject(report));
        metadata.put("Mail_Body", body(report, publicBaseUrl));
        return metadata;
    }

    private static String shorten(String text) {
        if (text == null) {
            return "-";
        }
        return text.length() > MAX_CAUSE_CHARS ? text.substring(0, MAX_CAUSE_CHARS) + "..." : text;
    }

    private static String esc(String text) {
        return text == null ? "" : HtmlUtils.htmlEscape(text, "UTF-8");
    }
}
