package com.rpatest.execution.engine;

import com.rpatest.execution.domain.StepRun;
import com.rpatest.scenario.domain.ScenarioStep;
import com.rpatest.scenario.domain.ScenarioStepType;

/** Strategy: одна реализация на тип шага сценария (JOB/QUEUE). */
public interface StepExecutor {

    ScenarioStepType supports();

    /**
     * Выполняет шаг против оркестратора и заполняет {@code stepRun} идентификаторами созданных
     * сущностей. Бросает {@link StepExecutionException} при неуспехе — вызывающий помечает
     * {@link StepRun} как FAILED.
     */
    void execute(StepRun stepRun, ScenarioStep step);
}
