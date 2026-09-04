package com.rpatest.execution.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.rpatest.execution.domain.StepRun;
import com.rpatest.execution.repository.StepRunRepository;
import org.junit.jupiter.api.Test;

class StepProgressReporterTest {

    @Test
    void updatesDetailAndPersistsStepRun() {
        StepRunRepository stepRunRepository = mock(StepRunRepository.class);
        StepProgressReporter reporter = new StepProgressReporter(stepRunRepository);
        StepRun stepRun = new StepRun(1L, 2L);

        reporter.report(stepRun, "выполняется на роботе X");

        assertThat(stepRun.getDetail()).isEqualTo("выполняется на роботе X");
        assertThat(stepRun.getDetailUpdatedAt()).isNotNull();
        verify(stepRunRepository).save(stepRun);
    }
}
