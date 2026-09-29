package com.rpatest.orchestrator.util;

import com.rpatest.orchestrator.dto.QueueItemProjectDto;
import com.rpatest.orchestrator.dto.RpaProjectLaunchDto;
import java.util.List;
import java.util.Map;

/**
 * Единственное место, где "сырые" данные оркестратора превращаются в человекочитаемую фразу для
 * {@code StepRun.detail}/логов/сообщений об ошибке. До выделения этого класса три места
 * (`StatusPoller`, `JobStepExecutor`, `QueueCheckStepExecutor`) независимо собирали такие фразы
 * конкатенацией строк — тот же паттерн дублирования, ради которого был выделен `QueueItemFinder`
 * (см. `agents.md`, "Списки элементов очереди"), только для текста, а не для чтения очереди.
 * Не делает HTTP-вызовов сама — вызывающая сторона получает данные через порты/{@code
 * OrchestratorLookup} и передаёт сюда уже готовые DTO.
 */
public final class OrchestratorNarration {

    private OrchestratorNarration() {
    }

    public static String describeRunning(RpaProjectLaunchDto latest) {
        return "выполняется на роботе '" + latest.robotName() + "' (начато " + latest.robotStartedAt() + ")";
    }

    public static String describeQueued(List<QueueItemProjectDto> queueEntries) {
        if (!queueEntries.isEmpty()) {
            return "в очереди проектов оркестратора (поставлено " + queueEntries.get(0).createdAt()
                    + "), ожидание свободного робота";
        }
        return "не найдено ни в очереди проектов, ни среди запусков на роботах";
    }

    /** Суффикс с текстом ошибки из очереди проектов (`RpaProjectQueue.errorMsg`), либо пустая строка. */
    public static String describeQueueError(List<QueueItemProjectDto> queueEntries) {
        return queueEntries.stream()
                .map(QueueItemProjectDto::errorMsg)
                .filter(msg -> msg != null && !msg.isBlank())
                .findFirst()
                .map(msg -> ": " + msg)
                .orElse("");
    }

    public static String describeExpectation(Map<String, Integer> expected, Integer minTotalCount) {
        StringBuilder sb = new StringBuilder();
        expected.forEach((status, count) -> sb.append(status).append(">=").append(count).append(" "));
        if (minTotalCount != null) {
            sb.append("(всего >= ").append(minTotalCount).append(")");
        }
        return sb.length() == 0 ? "(без конкретных ожиданий по количеству)" : sb.toString();
    }

    public static String describeActual(Map<String, Long> actualCounts, int actualTotal) {
        StringBuilder sb = new StringBuilder("всего=").append(actualTotal).append(" ");
        actualCounts.forEach((status, count) -> sb.append(status).append("=").append(count).append(" "));
        return sb.toString();
    }

    public static String describeCheckResult(
            Map<String, Integer> expected, Integer minTotalCount, Map<String, Long> actualCounts, int actualTotal) {
        return "Ожидалось: " + describeExpectation(expected, minTotalCount) + " — фактически: "
                + describeActual(actualCounts, actualTotal);
    }
}
