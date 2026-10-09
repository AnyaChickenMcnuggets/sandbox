package com.rpatest.execution.report;

import com.rpatest.execution.domain.RunStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Turns the structured outcome of a step into a plain Russian sentence for the report and the mail,
 * instead of showing the raw progress text or the (English, technical) exception message. The raw
 * error is kept separately as {@link Comment#technical()} for people who need the details.
 */
public final class StepComment {

    private static final String STOPPED_BY_USER = "Остановлено пользователем";

    private StepComment() {
    }

    /** @param text      what happened, in a sentence or two
     *  @param technical the original error text, or {@code null} when the text already says it all */
    public record Comment(String text, String technical) {
    }

    public static Comment describe(RunReportSnapshot report, RunReportSnapshot.Step step) {
        RunStatus status = step.status() == null ? RunStatus.PENDING : step.status();
        return switch (status) {
            case PENDING -> new Comment(notRun(report), null);
            case RUNNING -> new Comment("Шаг выполнялся в момент формирования отчёта.", null);
            case STOPPED -> new Comment(STOPPED_BY_USER + ".", null);
            case SUCCEEDED -> new Comment(succeeded(step), null);
            case FAILED -> failed(step);
        };
    }

    private static String notRun(RunReportSnapshot report) {
        if (report.status() == RunStatus.STOPPED) {
            return "Не выполнялся: прогон был остановлен.";
        }
        if (report.steps().stream().anyMatch(s -> s.status() == RunStatus.FAILED)) {
            return "Не выполнялся: один из предыдущих шагов завершился с ошибкой.";
        }
        if (report.startStepId() != null) {
            return "Не выполнялся: прогон начат с середины сценария.";
        }
        return "Не выполнялся.";
    }

    private static String succeeded(RunReportSnapshot.Step step) {
        Map<String, Object> result = step.result();
        if (result == null || step.type() == null) {
            return "Выполнен успешно.";
        }
        return switch (step.type()) {
            case JOB -> "Задание выполнено на роботе «" + text(result.get("robotName")) + "»"
                    + (step.durationSeconds() == null ? "" : " за " + ReportText.duration(step.durationSeconds())) + ".";
            case QUEUE -> queueSentence(result);
            case QUEUE_CHECK -> "Проверка пройдена: " + describeActual(result) + ".";
        };
    }

    private static Comment failed(RunReportSnapshot.Step step) {
        Map<String, Object> result = step.result();
        String error = step.errorMessage();
        if (STOPPED_BY_USER.equals(error)) {
            return new Comment(STOPPED_BY_USER + ".", null);
        }
        if (result != null && step.type() != null) {
            switch (step.type()) {
                case JOB -> {
                    String robotError = text(result.get("robotError"));
                    return new Comment("Задание завершилось с ошибкой на роботе «" + text(result.get("robotName")) + "»."
                            + (robotError.isEmpty() ? "" : " Сообщение робота: " + robotError), error);
                }
                case QUEUE_CHECK -> {
                    return new Comment("Проверка не пройдена: " + shortfalls(result) + reasonSentence(result), error);
                }
                default -> {
                }
            }
        }
        if (error == null || error.isBlank()) {
            return new Comment("Шаг завершился с ошибкой, текст ошибки не сохранён.", null);
        }
        return new Comment("Шаг завершился с ошибкой. Подробности - в технических деталях.", error);
    }

    private static String queueSentence(Map<String, Object> result) {
        String queue = "«" + text(result.get("queueName")) + "»";
        long added = number(result.get("transactionsAdded"));
        String origin = Boolean.TRUE.equals(result.get("created"))
                ? "Очередь " + queue + " создана этим прогоном"
                : "Использована уже существующая очередь " + queue;
        return origin + ", добавлено транзакций: " + added + ".";
    }

    /** "получено транзакций: 3 (Успешно - 3)". */
    private static String describeActual(Map<String, Object> result) {
        long total = number(result.get("actualTotal"));
        List<String> parts = new ArrayList<>();
        asMap(result.get("actual")).forEach((status, count) ->
                parts.add(ReportText.queueStatusLabel(status) + " - " + number(count)));
        return "получено транзакций: " + total + (parts.isEmpty() ? "" : " (" + String.join(", ", parts) + ")");
    }

    /** "«Успешно»: ожидалось не менее 5, получено 2; всего: ожидалось не менее 7, получено 2." */
    private static String shortfalls(Map<String, Object> result) {
        Map<String, Object> actual = asMap(result.get("actual"));
        List<String> parts = new ArrayList<>();
        asMap(result.get("expected")).forEach((status, want) -> {
            long got = number(actual.get(status));
            if (got < number(want)) {
                parts.add("«" + ReportText.queueStatusLabel(status) + "»: ожидалось не менее " + number(want)
                        + ", получено " + got);
            }
        });
        Object minTotal = result.get("minTotalCount");
        if (minTotal != null && number(result.get("actualTotal")) < number(minTotal)) {
            parts.add("всего: ожидалось не менее " + number(minTotal) + ", получено " + number(result.get("actualTotal")));
        }
        return parts.isEmpty() ? "условия проверки не выполнены." : String.join("; ", parts) + ".";
    }

    private static String reasonSentence(Map<String, Object> result) {
        String reason = text(result.get("failureReason"));
        return switch (reason) {
            case "ALL_FINAL" -> " Все отслеживаемые транзакции уже получили конечный статус, дальнейшее ожидание не имело смысла.";
            case "TIMEOUT" -> " Истекло время ожидания, заданное в шаге.";
            default -> "";
        };
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static long number(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString();
    }
}
