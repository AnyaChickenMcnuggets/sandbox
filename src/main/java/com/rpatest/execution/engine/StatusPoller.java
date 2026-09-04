package com.rpatest.execution.engine;

import com.rpatest.config.OrchestratorProperties;
import com.rpatest.execution.domain.StepRun;
import com.rpatest.orchestrator.client.RpaProjectLaunchesPort;
import com.rpatest.orchestrator.client.RpaProjectQueuePort;
import com.rpatest.orchestrator.dto.QueueItemProjectDto;
import com.rpatest.orchestrator.dto.RpaProjectLaunchDto;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Отслеживает реальное выполнение задания: не по {@code AssignmentStatus} (он лишь отражает, что
 * оркестратор поставил проект в очередь выполнения — {@code RpaProjectQueue}), а по фактическому
 * запуску на роботе ({@code RpaProjectLaunches}). Пока запись о запуске не появилась, задание
 * либо ещё в очереди ожидания, либо ещё не подхвачено ни одним роботом; появление записи с
 * {@code completedAt}/{@code killedAt} — единственный надёжный сигнал реального завершения.
 * На каждой итерации публикует текущее состояние через {@link StepProgressReporter} — иначе
 * долгий (минуты) поллинг снаружи неотличим от зависания.
 */
@Component
public class StatusPoller {

    private static final Logger log = LoggerFactory.getLogger(StatusPoller.class);

    private final RpaProjectLaunchesPort rpaProjectLaunchesPort;
    private final RpaProjectQueuePort rpaProjectQueuePort;
    private final StepProgressReporter progressReporter;
    private final OrchestratorProperties properties;

    public StatusPoller(
            RpaProjectLaunchesPort rpaProjectLaunchesPort,
            RpaProjectQueuePort rpaProjectQueuePort,
            StepProgressReporter progressReporter,
            OrchestratorProperties properties) {
        this.rpaProjectLaunchesPort = rpaProjectLaunchesPort;
        this.rpaProjectQueuePort = rpaProjectQueuePort;
        this.progressReporter = progressReporter;
        this.properties = properties;
    }

    public RpaProjectLaunchDto pollUntilTerminal(StepRun stepRun, int assignmentId) {
        Duration interval = properties.getPolling().getInterval();
        Instant deadline = Instant.now().plus(properties.getPolling().getTimeout());
        int attempt = 0;
        while (true) {
            attempt++;
            List<RpaProjectLaunchDto> launches = rpaProjectLaunchesPort.getByAssignment(assignmentId);
            RpaProjectLaunchDto latest = latestLaunch(launches);
            if (latest != null && latest.isTerminal()) {
                progressReporter.report(stepRun, "Задание id=" + assignmentId + " завершилось на роботе '"
                        + latest.robotName() + "': " + (latest.isSuccess() ? "успешно" : "с ошибкой"));
                return latest;
            }

            String state = describeState(assignmentId, latest);
            log.debug("Попытка #{} опроса задания id={}: {}", attempt, assignmentId, state);
            progressReporter.report(stepRun, "Задание id=" + assignmentId + " " + state + " (попытка #" + attempt + ")");

            if (Instant.now().isAfter(deadline)) {
                throw new StepExecutionException(
                        "Таймаут ожидания завершения задания id=" + assignmentId + ". Последнее известное состояние: "
                                + state);
            }
            sleep(interval);
        }
    }

    private String describeState(int assignmentId, RpaProjectLaunchDto latest) {
        if (latest != null) {
            return "выполняется на роботе '" + latest.robotName() + "' (начато " + latest.robotStartedAt() + ")";
        }
        List<QueueItemProjectDto> queued = rpaProjectQueuePort.findByAssignment(assignmentId);
        if (!queued.isEmpty()) {
            return "в очереди проектов оркестратора (поставлено " + queued.get(0).createdAt()
                    + "), ожидание свободного робота";
        }
        return "не найдено ни в очереди проектов, ни среди запусков на роботах";
    }

    private RpaProjectLaunchDto latestLaunch(List<RpaProjectLaunchDto> launches) {
        return launches.stream().max(Comparator.comparing(RpaProjectLaunchDto::startedAt)).orElse(null);
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
