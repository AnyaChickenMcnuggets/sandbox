package com.rpatest.execution.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.rpatest.execution.domain.RunStatus;
import com.rpatest.execution.domain.ScenarioRun;
import com.rpatest.execution.domain.StepRun;
import com.rpatest.execution.repository.ScenarioRunRepository;
import com.rpatest.execution.repository.StepRunRepository;
import com.rpatest.scenario.domain.ScenarioStep;
import com.rpatest.scenario.domain.ScenarioStepEdge;
import com.rpatest.scenario.domain.ScenarioStepType;
import com.rpatest.scenario.repository.ScenarioStepEdgeRepository;
import com.rpatest.scenario.repository.ScenarioStepRepository;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ScenarioExecutionEngineTest {

    private ScenarioStepRepository stepRepository;
    private ScenarioStepEdgeRepository edgeRepository;
    private ScenarioRunRepository runRepository;
    private StepRunRepository stepRunRepository;
    private Map<Long, StepRun> savedStepRunsById;
    private ScenarioRun run;

    @BeforeEach
    void setUp() {
        stepRepository = mock(ScenarioStepRepository.class);
        edgeRepository = mock(ScenarioStepEdgeRepository.class);
        runRepository = mock(ScenarioRunRepository.class);
        stepRunRepository = mock(StepRunRepository.class);
        savedStepRunsById = new ConcurrentHashMap<>();

        run = new ScenarioRun(100L, "tester");
        setId(run, 10L);
        when(runRepository.findById(10L)).thenReturn(Optional.of(run));
        when(runRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AtomicLong stepRunIdSeq = new AtomicLong(1);
        when(stepRunRepository.save(any())).thenAnswer(inv -> {
            StepRun stepRun = inv.getArgument(0);
            if (stepRun.getId() == null) {
                setId(stepRun, stepRunIdSeq.getAndIncrement());
            }
            savedStepRunsById.put(stepRun.getId(), stepRun);
            return stepRun;
        });
        when(stepRunRepository.findByScenarioRunId(10L)).thenAnswer(inv -> List.copyOf(savedStepRunsById.values()));
    }

    @Test
    void executesFanOutBranchesAfterJobSucceeds() {
        ScenarioStep job = step(1L, ScenarioStepType.JOB, "job");
        ScenarioStep queueA = step(2L, ScenarioStepType.QUEUE, "queueA");
        ScenarioStep queueB = step(3L, ScenarioStepType.QUEUE, "queueB");
        when(stepRepository.findByScenarioIdOrderByPosition(100L)).thenReturn(List.of(job, queueA, queueB));
        when(edgeRepository.findByStepIds(any())).thenReturn(List.of(
                new ScenarioStepEdge(1L, 2L), new ScenarioStepEdge(1L, 3L)));

        StepExecutor succeedingExecutor = new RecordingExecutor(null);
        ScenarioExecutionEngine engine = new ScenarioExecutionEngine(
                stepRepository, edgeRepository, runRepository, stepRunRepository,
                List.of(succeedingExecutor, alsoSupports(succeedingExecutor, ScenarioStepType.QUEUE)),
                Runnable::run);

        engine.runScenario(10L);

        Set<Long> executedStepIds = savedStepRunsById.values().stream().map(StepRun::getStepId).collect(java.util.stream.Collectors.toSet());
        assertThat(executedStepIds).containsExactlyInAnyOrder(1L, 2L, 3L);
        assertThat(run.getStatus()).isEqualTo(RunStatus.SUCCEEDED);
    }

    @Test
    void skipsChildStepsWhenParentFails() {
        ScenarioStep job = step(1L, ScenarioStepType.JOB, "job");
        ScenarioStep queueA = step(2L, ScenarioStepType.QUEUE, "queueA");
        when(stepRepository.findByScenarioIdOrderByPosition(100L)).thenReturn(List.of(job, queueA));
        when(edgeRepository.findByStepIds(any())).thenReturn(List.of(new ScenarioStepEdge(1L, 2L)));

        StepExecutor failingJobExecutor = new RecordingExecutor("boom") {
            @Override
            public ScenarioStepType supports() {
                return ScenarioStepType.JOB;
            }
        };
        StepExecutor queueExecutor = new RecordingExecutor(null) {
            @Override
            public ScenarioStepType supports() {
                return ScenarioStepType.QUEUE;
            }
        };

        ScenarioExecutionEngine engine = new ScenarioExecutionEngine(
                stepRepository, edgeRepository, runRepository, stepRunRepository,
                List.of(failingJobExecutor, queueExecutor), Runnable::run);

        engine.runScenario(10L);

        assertThat(savedStepRunsById).hasSize(1);
        assertThat(savedStepRunsById.values().iterator().next().getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(run.getStatus()).isEqualTo(RunStatus.FAILED);
    }

    @Test
    void errorMessageIncludesRootCauseNotJustWrapperText() {
        ScenarioStep job = step(1L, ScenarioStepType.JOB, "job");
        when(stepRepository.findByScenarioIdOrderByPosition(100L)).thenReturn(List.of(job));
        when(edgeRepository.findByStepIds(any())).thenReturn(List.of());

        RuntimeException rootCause = new RuntimeException("500 [no body]");
        StepExecutor executorThatWrapsACause = new StepExecutor() {
            @Override
            public ScenarioStepType supports() {
                return ScenarioStepType.JOB;
            }

            @Override
            public void execute(StepRun stepRun, ScenarioStep step) {
                throw new StepExecutionException("Не удалось выполнить проверку очереди 'x'", rootCause);
            }
        };

        ScenarioExecutionEngine engine = new ScenarioExecutionEngine(
                stepRepository, edgeRepository, runRepository, stepRunRepository,
                List.of(executorThatWrapsACause), Runnable::run);

        engine.runScenario(10L);

        String errorMessage = savedStepRunsById.values().iterator().next().getErrorMessage();
        assertThat(errorMessage).contains("Не удалось выполнить проверку очереди 'x'");
        assertThat(errorMessage).contains("500 [no body]");
    }

    @Test
    void doesNotDeadlockOnLinearChainLongerThanExecutorThreadCount() {
        // Регрессия: до фикса runScenario->executeStepRecursively рекурсивно звал
        // CompletableFuture.runAsync(...).join() на каждом уровне цепочки — каждый уровень навсегда
        // занимал отдельный поток пула, ожидая следующий. На пуле с 2 потоками и цепочкой из 6
        // последовательных шагов (queueIn->queueOut->job->checkInput->checkOutput и т.п.) 3-й
        // уровень уже не находил свободный поток и вис вечно, хотя очередь задач не была заполнена
        // (ThreadPoolExecutor не создаёт новые потоки сверх занятых core, пока очередь не полна).
        List<ScenarioStep> chain = List.of(
                step(1L, ScenarioStepType.QUEUE, "s1"), step(2L, ScenarioStepType.QUEUE, "s2"),
                step(3L, ScenarioStepType.JOB, "s3"), step(4L, ScenarioStepType.QUEUE, "s4"),
                step(5L, ScenarioStepType.QUEUE, "s5"), step(6L, ScenarioStepType.QUEUE, "s6"));
        when(stepRepository.findByScenarioIdOrderByPosition(100L)).thenReturn(chain);
        when(edgeRepository.findByStepIds(any())).thenReturn(List.of(
                new ScenarioStepEdge(1L, 2L), new ScenarioStepEdge(2L, 3L), new ScenarioStepEdge(3L, 4L),
                new ScenarioStepEdge(4L, 5L), new ScenarioStepEdge(5L, 6L)));

        StepExecutor succeedingExecutor = new RecordingExecutor(null);
        ExecutorService boundedPool = Executors.newFixedThreadPool(2);
        try {
            ScenarioExecutionEngine engine = new ScenarioExecutionEngine(
                    stepRepository, edgeRepository, runRepository, stepRunRepository,
                    List.of(succeedingExecutor, alsoSupports(succeedingExecutor, ScenarioStepType.QUEUE)),
                    boundedPool);

            assertTimeoutPreemptively(Duration.ofSeconds(10), () -> engine.runScenario(10L));
        } finally {
            boundedPool.shutdownNow();
        }

        assertThat(savedStepRunsById).hasSize(6);
        assertThat(run.getStatus()).isEqualTo(RunStatus.SUCCEEDED);
    }

    private StepExecutor alsoSupports(StepExecutor delegate, ScenarioStepType type) {
        return new StepExecutor() {
            @Override
            public ScenarioStepType supports() {
                return type;
            }

            @Override
            public void execute(StepRun stepRun, ScenarioStep step) {
                delegate.execute(stepRun, step);
            }
        };
    }

    private ScenarioStep step(Long id, ScenarioStepType type, String name) {
        ScenarioStep step = new ScenarioStep(100L, type, name, Map.of(), 0);
        setId(step, id);
        return step;
    }

    private void setId(Object entity, Long id) {
        try {
            Field field = entity.getClass().getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static class RecordingExecutor implements StepExecutor {
        private final String failureMessage;

        RecordingExecutor(String failureMessage) {
            this.failureMessage = failureMessage;
        }

        @Override
        public ScenarioStepType supports() {
            return ScenarioStepType.JOB;
        }

        @Override
        public void execute(StepRun stepRun, ScenarioStep step) {
            if (failureMessage != null) {
                throw new StepExecutionException(failureMessage);
            }
        }
    }
}
