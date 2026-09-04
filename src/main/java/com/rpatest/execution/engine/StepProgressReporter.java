package com.rpatest.execution.engine;

import com.rpatest.execution.domain.StepRun;
import com.rpatest.execution.repository.StepRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Публикует человекочитаемый прогресс шага ({@code StepRun.detail}) наружу (через
 * {@code GET /api/v1/runs/{runId}}) по ходу выполнения, а не только по завершении. Без этого шаг,
 * который выполняется 10+ минут (ожидание робота, поллинг очереди), снаружи неотличим от
 * зависшего — виден только статус {@code RUNNING} без какого-либо объяснения. Также дублирует
 * каждое сообщение в лог — по просьбе пользователя логов должно быть много, а не 0.
 */
@Component
public class StepProgressReporter {

    private static final Logger log = LoggerFactory.getLogger(StepProgressReporter.class);

    private final StepRunRepository stepRunRepository;

    public StepProgressReporter(StepRunRepository stepRunRepository) {
        this.stepRunRepository = stepRunRepository;
    }

    public void report(StepRun stepRun, String detail) {
        stepRun.updateDetail(detail);
        stepRunRepository.save(stepRun);
        log.info("[step_run={}] {}", stepRun.getId(), detail);
    }
}
