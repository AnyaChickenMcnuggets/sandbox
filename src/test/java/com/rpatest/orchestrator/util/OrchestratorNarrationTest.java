package com.rpatest.orchestrator.util;

import static org.assertj.core.api.Assertions.assertThat;

import com.rpatest.orchestrator.dto.QueueItemProjectDto;
import com.rpatest.orchestrator.dto.RpaProjectLaunchDto;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OrchestratorNarrationTest {

    @Test
    void describesRunningWithRobotNameAndStartTime() {
        LocalDateTime startedAt = LocalDateTime.now();
        RpaProjectLaunchDto launch = new RpaProjectLaunchDto(1, 7, 5, "robot-1", 1, startedAt, null, null, null, startedAt);

        String result = OrchestratorNarration.describeRunning(launch);

        assertThat(result).contains("robot-1").contains(startedAt.toString());
    }

    @Test
    void describesQueuedWhenEntriesPresent() {
        LocalDateTime createdAt = LocalDateTime.now();
        List<QueueItemProjectDto> queued = List.of(new QueueItemProjectDto(1, 1, null, null, createdAt, null));

        String result = OrchestratorNarration.describeQueued(queued);

        assertThat(result).contains("в очереди проектов").contains(createdAt.toString());
    }

    @Test
    void describesNotFoundWhenNoEntries() {
        String result = OrchestratorNarration.describeQueued(List.of());

        assertThat(result).contains("не найдено ни в очереди проектов, ни среди запусков");
    }

    @Test
    void describesQueueErrorFromFirstNonBlankMessage() {
        List<QueueItemProjectDto> entries = List.of(
                new QueueItemProjectDto(1, 1, null, null, LocalDateTime.now(), null),
                new QueueItemProjectDto(2, 1, "boom", "robot-1", LocalDateTime.now(), null));

        String result = OrchestratorNarration.describeQueueError(entries);

        assertThat(result).isEqualTo(": boom");
    }

    @Test
    void describesQueueErrorAsEmptyWhenNoMessage() {
        String result = OrchestratorNarration.describeQueueError(List.of());

        assertThat(result).isEmpty();
    }

    @Test
    void describesExpectationWithThresholdsAndMinTotal() {
        String result = OrchestratorNarration.describeExpectation(Map.of("SUCCESS", 2), 5);

        assertThat(result).contains("SUCCESS>=2").contains("всего >= 5");
    }

    @Test
    void describesExpectationAsNoneWhenEmpty() {
        String result = OrchestratorNarration.describeExpectation(Map.of(), null);

        assertThat(result).isEqualTo("(без конкретных ожиданий по количеству)");
    }

    @Test
    void describesActualCounts() {
        String result = OrchestratorNarration.describeActual(Map.of("SUCCESS", 3L), 3);

        assertThat(result).contains("всего=3").contains("SUCCESS=3");
    }

    @Test
    void describesCheckResultCombiningExpectedAndActual() {
        String result = OrchestratorNarration.describeCheckResult(Map.of("SUCCESS", 5), null, Map.of("SUCCESS", 2L), 2);

        assertThat(result).contains("Ожидалось:").contains("SUCCESS>=5").contains("фактически:").contains("SUCCESS=2");
    }
}
