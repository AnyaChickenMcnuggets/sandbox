package com.rpatest.execution.report;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns the text the engine stored for a step ({@code StepRun.detail}, {@code errorMessage}) into
 * readable Russian for the report. Nothing is dropped: the engine's messages are English and ASCII
 * (they go to the terminal log), so every known template is translated phrase by phrase and
 * whatever is not recognised (a robot's own error text, an HTTP client message, the whole message
 * of a run recorded before the messages were translated) stays as it is. Dates and counts are
 * made readable on the way. Pure and total: never throws, never returns {@code null} for text.
 */
public final class ReportMessages {

    private record Rule(Pattern pattern, String replacement) {
        static Rule of(String regex, String replacement) {
            return new Rule(Pattern.compile(regex), replacement);
        }
    }

    /** "Expected: A - actual: B" in the new (English) and the old (Russian) wording, with or without B. */
    private static final Pattern EXPECTATION_CLAUSE = Pattern.compile(
            "(?:Expected|Ожидалось|Ожидается): (.*?)(?:\\s+(?:-|—) (?:actual|фактически): (.*?))?\\s*$", Pattern.DOTALL);
    private static final Pattern EXPECTATION_TOKEN = Pattern.compile(
            "([A-Z][A-Z_]*)>=(\\d+)|\\((?:total|всего) >= (\\d+)\\)"
                    + "|\\((?:no specific count expectations|без конкретных ожиданий по количеству)\\)");
    private static final Pattern ACTUAL_COUNTS = Pattern.compile("(?:total|всего)=(\\d+)((?:\\s+[A-Z][A-Z_]*=\\d+)*)");
    private static final Pattern STATUS_COUNT = Pattern.compile("([A-Z][A-Z_]*)=(\\d+)");
    private static final Pattern ISO_DATE_TIME = Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2})T(\\d{2}):(\\d{2}):(\\d{2})(?:\\.\\d+)?");
    private static final Pattern QUEUE_ID = Pattern.compile("\\s*\\(id=[0-9a-fA-F-]{36}\\)");
    /** A single quoted word (a queue, an assignment or a robot name) becomes «word»; apostrophes in free text are left alone. */
    private static final Pattern QUOTED_WORD = Pattern.compile("'([^'\\s]+)'");

    private static final List<Rule> RULES = List.of(
            // robot launch states (StatusPoller / OrchestratorNarration)
            Rule.of("\\(attempt #(\\d+)\\)", "(попытка $1)"),
            Rule.of("running on robot '([^']*)' \\(started ([^)]*)\\)", "выполняется на роботе «$1» (старт $2)"),
            Rule.of("in the orchestrator project queue \\(enqueued ([^)]*)\\), waiting for a free robot",
                    "в очереди проектов оркестратора (поставлено $1), ожидает свободного робота"),
            Rule.of("not found in the project queue nor among robot launches",
                    "не найдено ни в очереди проектов, ни среди запусков на роботах"),
            Rule.of("Assignment '([^']*)' finished on robot '([^']*)': successfully", "Задание «$1» завершилось на роботе «$2»: успешно"),
            Rule.of("Assignment '([^']*)' finished on robot '([^']*)': with an error",
                    "Задание «$1» завершилось на роботе «$2»: с ошибкой"),
            Rule.of("Timed out waiting for assignment '([^']*)' to finish\\. Last known state: ",
                    "Истекло время ожидания завершения задания «$1». Последнее известное состояние: "),
            Rule.of("Wait for assignment completion was interrupted", "Ожидание завершения задания было прервано"),
            Rule.of("Assignment failed on robot '([^']*)'", "Задание завершилось с ошибкой на роботе «$1»"),
            Rule.of("Assignment '([^']*)' not found in the orchestrator after creation \\(empty POST response\\)",
                    "Задание «$1» не найдено в оркестраторе после создания (пустой ответ на создание)"),
            Rule.of("Assignment '([^']*)' ", "Задание «$1» "),
            // job step
            Rule.of("Creating assignment '([^']*)' for project '([^']*)'", "Создание задания «$1» по проекту «$2»"),
            Rule.of("Starting assignment '([^']*)'", "Запуск задания «$1»"),
            Rule.of("Setting assignment arguments '([^']*)': ", "Установка аргументов задания «$1»: "),
            Rule.of("Failed to execute job step '([^']*)'", "Не удалось выполнить шаг-задание «$1»"),
            Rule.of("Step '([^']*)' has neither rpaProjectName nor rpaProjectId", "В шаге «$1» не указан ни rpaProjectName, ни rpaProjectId"),
            Rule.of("Project '([^']*)' not found in the orchestrator", "Проект «$1» не найден в оркестраторе"),
            // queue step
            Rule.of("Looking up/creating queue '([^']*)' for the check", "Поиск или создание очереди «$1» для проверки"),
            Rule.of("Looking up/creating queue '([^']*)'", "Поиск или создание очереди «$1»"),
            Rule.of("Transactions added: (\\d+)/(\\d+) \\(last naturalKey='([^']*)'\\)",
                    "Добавлено транзакций: $1 из $2 (последняя naturalKey «$3»)"),
            Rule.of("Queue '([^']*)' is ready, transactions added: (\\d+)", "Очередь «$1» готова, добавлено транзакций: $2"),
            Rule.of("Failed to execute queue step '([^']*)'", "Не удалось выполнить шаг очереди «$1»"),
            Rule.of("Queue '([^']*)' not found in the orchestrator right after creation",
                    "Очередь «$1» не найдена в оркестраторе сразу после создания"),
            // queue check step
            Rule.of("Queue '([^']*)'(?: \\(id=[0-9a-fA-F-]+\\))? found, starting the check\\.", "Очередь «$1» найдена, начинаю проверку."),
            Rule.of("Checking queue '([^']*)' ", "Проверка очереди «$1» "),
            Rule.of("Queue check '([^']*)' passed:", "Проверка очереди «$1» пройдена:"),
            Rule.of("Queue check '([^']*)' stopped early: all tracked transactions \\((\\d+)\\) already have a final status,"
                    + " further waiting is pointless",
                    "Проверка очереди «$1» прекращена досрочно: все отслеживаемые транзакции ($2) уже получили конечный статус,"
                            + " дальнейшее ожидание бессмысленно"),
            Rule.of("Queue check '([^']*)' failed: all tracked transactions already have a final status that will not change\\.",
                    "Проверка очереди «$1» не пройдена: все отслеживаемые транзакции уже получили конечный статус,"
                            + " который не изменится."),
            Rule.of("Queue check '([^']*)' did not pass within the allotted time\\.",
                    "Проверка очереди «$1» не пройдена за отведённое время."),
            Rule.of("Queue check wait was interrupted", "Ожидание проверки очереди было прервано"),
            Rule.of("No queue name to check in step '([^']*)'", "Не указано имя очереди для проверки в шаге «$1»"),
            Rule.of("Failed to run queue check '([^']*)'", "Не удалось выполнить проверку очереди «$1»"),
            // orchestrator and engine
            Rule.of("Orchestrator call failed: ", "Ошибка вызова оркестратора: "),
            Rule.of("Failed to authenticate in the orchestrator", "Не удалось выполнить аутентификацию в оркестраторе"),
            Rule.of("Empty orchestrator response during authentication", "Пустой ответ оркестратора при аутентификации"),
            Rule.of("Failed to parse the orchestrator authentication response",
                    "Не удалось разобрать ответ оркестратора при аутентификации"),
            Rule.of("Authentication response has no recognizable token field: ",
                    "В ответе аутентификации нет распознаваемого поля токена: "),
            Rule.of("ScenarioRun not found: (\\d+)", "Прогон не найден: $1"),
            Rule.of("Step (\\d+) not found in scenario (\\d+)", "Шаг $1 не найден в сценарии $2"));

    private ReportMessages() {
    }

    public static String translate(String raw) {
        if (raw == null || raw.isBlank()) {
            return raw == null ? "" : raw;
        }
        String text = EXPECTATION_CLAUSE.matcher(raw).replaceFirst(ReportMessages::expectation);
        text = ACTUAL_COUNTS.matcher(text).replaceAll(ReportMessages::actualCounts);
        for (Rule rule : RULES) {
            text = rule.pattern().matcher(text).replaceAll(rule.replacement());
        }
        text = ISO_DATE_TIME.matcher(text).replaceAll("$3.$2.$1 $4:$5:$6");
        text = QUEUE_ID.matcher(text).replaceAll("");
        text = QUOTED_WORD.matcher(text).replaceAll("«$1»");
        return text.trim();
    }

    /**
     * True if the text has engine (English) wording that {@link #translate} turns into Russian, as
     * opposed to a text that only gets cosmetic changes (quotes, dates) - the report shows the
     * original next to a real translation, not next to a prettified copy of itself.
     */
    public static boolean wasTranslated(String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        return raw.contains("Expected: ") || RULES.stream().anyMatch(rule -> rule.pattern().matcher(raw).find());
    }

    private static String expectation(MatchResult clause) {
        StringBuilder text = new StringBuilder("Ожидалось: ").append(expectedParts(clause.group(1)));
        if (clause.group(2) != null) {
            text.append(". Получено: ").append(ACTUAL_COUNTS.matcher(clause.group(2).trim()).replaceAll(ReportMessages::actualCounts));
        }
        return Matcher.quoteReplacement(text.toString());
    }

    private static String expectedParts(String expected) {
        List<String> parts = new ArrayList<>();
        Matcher token = EXPECTATION_TOKEN.matcher(expected);
        while (token.find()) {
            if (token.group(1) != null) {
                parts.add(ReportText.queueStatusLabel(token.group(1)) + ": не менее " + token.group(2));
            } else if (token.group(3) != null) {
                parts.add("всего: не менее " + token.group(3));
            } else {
                parts.add("конкретных ожиданий по количеству нет");
            }
        }
        return parts.isEmpty() ? expected.trim() : String.join(", ", parts);
    }

    private static String actualCounts(MatchResult counts) {
        List<String> byStatus = new ArrayList<>();
        Matcher status = STATUS_COUNT.matcher(counts.group(2) == null ? "" : counts.group(2));
        while (status.find()) {
            byStatus.add(ReportText.queueStatusLabel(status.group(1)) + ": " + status.group(2));
        }
        return Matcher.quoteReplacement("всего " + counts.group(1) + (byStatus.isEmpty() ? "" : " (" + String.join(", ", byStatus) + ")"));
    }
}
