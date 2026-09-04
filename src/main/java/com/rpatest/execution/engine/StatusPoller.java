package com.rpatest.execution.engine;

import com.rpatest.config.OrchestratorProperties;
import com.rpatest.orchestrator.client.RpaProjectLaunchesPort;
import com.rpatest.orchestrator.client.RpaProjectQueuePort;
import com.rpatest.orchestrator.dto.QueueItemProjectDto;
import com.rpatest.orchestrator.dto.RpaProjectLaunchDto;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Отслеживает реальное выполнение задания: не по {@code AssignmentStatus} (он лишь отражает, что
 * оркестратор поставил проект в очередь выполнения — {@code RpaProjectQueue}), а по фактическому
 * запуску на роботе ({@code RpaProjectLaunches}). Пока запись о запуске не появилась, задание
 * либо ещё в очереди ожидания, либо ещё не подхвачено ни одним роботом; появление записи с
 * {@code completedAt}/{@code killedAt} — единственный надёжный сигнал реального завершения.
 */
@Component
public class StatusPoller {

    private final RpaProjectLaunchesPort rpaProjectLaunchesPort;
    private final RpaProjectQueuePort rpaProjectQueuePort;
    private final OrchestratorProperties properties;

    public StatusPoller(
            RpaProjectLaunchesPort rpaProjectLaunchesPort,
            RpaProjectQueuePort rpaProjectQueuePort,
            OrchestratorProperties properties) {
        this.rpaProjectLaunchesPort = rpaProjectLaunchesPort;
        this.rpaProjectQueuePort = rpaProjectQueuePort;
        this.properties = properties;
    }

    public RpaProjectLaunchDto pollUntilTerminal(int assignmentId) {
        Duration interval = properties.getPolling().getInterval();
        Instant deadline = Instant.now().plus(properties.getPolling().getTimeout());
        while (true) {
            RpaProjectLaunchDto latest = latestLaunch(rpaProjectLaunchesPort.getByAssignment(assignmentId));
            if (latest != null && latest.isTerminal()) {
                return latest;
            }
            if (Instant.now().isAfter(deadline)) {
                throw new StepExecutionException(buildTimeoutMessage(assignmentId, latest));
            }
            sleep(interval);
        }
    }

    private RpaProjectLaunchDto latestLaunch(List<RpaProjectLaunchDto> launches) {
        return launches.stream().max(Comparator.comparing(RpaProjectLaunchDto::startedAt)).orElse(null);
    }

    private String buildTimeoutMessage(int assignmentId, RpaProjectLaunchDto latest) {
        if (latest != null) {
            return "Таймаут ожидания завершения задания id=" + assignmentId + ": запущено на роботе '"
                    + latest.robotName() + "' в " + latest.robotStartedAt() + ", но так и не завершилось";
        }
        List<QueueItemProjectDto> queued = rpaProjectQueuePort.findByAssignment(assignmentId);
        if (!queued.isEmpty()) {
            return "Таймаут ожидания запуска задания id=" + assignmentId + ": всё ещё в очереди проектов "
                    + "(поставлено " + queued.get(0).createdAt() + "), ни один робот его не подхватил";
        }
        return "Таймаут ожидания задания id=" + assignmentId
                + ": не найдено ни в очереди проектов, ни среди запусков на роботах";
    }

    private void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StepExecutionException("Ожидание завершения задания было прервано", e);
        }
    }
}
