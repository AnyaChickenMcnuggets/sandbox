package com.rpatest.execution.report;

import com.rpatest.execution.domain.RunStatus;
import com.rpatest.scenario.domain.ScenarioStepType;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.Locale;
import java.util.Optional;

/** Russian wording and formatting shared by the HTML report and the notification mail. */
public final class ReportText {

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private ReportText() {
    }

    public static String dateTime(OffsetDateTime time) {
        return time == null ? "-" : DATE_TIME.format(time.atZoneSameInstant(ZoneId.systemDefault()));
    }

    public static String date(OffsetDateTime time) {
        return time == null ? "-" : DATE.format(time.atZoneSameInstant(ZoneId.systemDefault()));
    }

    /** Orchestrator timestamps come as ISO local date-time strings inside step results. */
    public static String dateTime(String isoLocalDateTime) {
        if (isoLocalDateTime == null || isoLocalDateTime.isBlank()) {
            return "-";
        }
        try {
            return DATE_TIME.format(LocalDateTime.parse(isoLocalDateTime));
        } catch (DateTimeParseException e) {
            return isoLocalDateTime;
        }
    }

    public static String duration(Long seconds) {
        if (seconds == null) {
            return "-";
        }
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;
        if (hours > 0) {
            return String.format(Locale.ROOT, "%d ч %02d мин %02d с", hours, minutes, secs);
        }
        if (minutes > 0) {
            return String.format(Locale.ROOT, "%d мин %02d с", minutes, secs);
        }
        return secs + " с";
    }

    public static String statusLabel(RunStatus status) {
        if (status == null) {
            return "-";
        }
        return switch (status) {
            case SUCCEEDED -> "Успешно";
            case FAILED -> "Ошибка";
            case RUNNING -> "Выполняется";
            case STOPPED -> "Остановлен";
            case PENDING -> "Не выполнялся";
        };
    }

    public static String verdictLabel(RunStatus status) {
        if (status == null) {
            return "НЕ ЗАВЕРШЁН";
        }
        return switch (status) {
            case SUCCEEDED -> "УСПЕШНО";
            case FAILED -> "ЕСТЬ ОШИБКИ";
            case STOPPED -> "ОСТАНОВЛЕН ПОЛЬЗОВАТЕЛЕМ";
            case RUNNING, PENDING -> "НЕ ЗАВЕРШЁН";
        };
    }

    public static String stepTypeLabel(ScenarioStepType type) {
        if (type == null) {
            return "-";
        }
        return switch (type) {
            case JOB -> "Задание";
            case QUEUE -> "Очередь";
            case QUEUE_CHECK -> "Проверка очереди";
        };
    }

    public static String queueStatusLabel(String status) {
        if (status == null) {
            return "-";
        }
        return switch (status) {
            case "NEW" -> "Новая";
            case "IN_PROGRESS" -> "В работе";
            case "SUCCESS" -> "Успешно";
            case "ERROR" -> "Ошибка";
            case "BUSINESS_ERROR" -> "Бизнес-ошибка";
            default -> status;
        };
    }

    public static long count(RunReportSnapshot snapshot, RunStatus status) {
        return snapshot.steps().stream().filter(s -> s.status() == status).count();
    }

    /** The step that failed first (by start time) - usually the root cause, later ones are fallout. */
    public static Optional<RunReportSnapshot.Step> firstFailed(RunReportSnapshot snapshot) {
        return snapshot.steps().stream()
                .filter(s -> s.status() == RunStatus.FAILED)
                .min(Comparator.comparing(RunReportSnapshot.Step::startedAt,
                        Comparator.nullsLast(Comparator.naturalOrder())));
    }

    public static String stepsSummary(RunReportSnapshot snapshot) {
        return snapshot.steps().size() + " (успешно " + count(snapshot, RunStatus.SUCCEEDED)
                + ", с ошибками " + count(snapshot, RunStatus.FAILED)
                + ", не выполнялось " + count(snapshot, RunStatus.PENDING) + ")";
    }
}
