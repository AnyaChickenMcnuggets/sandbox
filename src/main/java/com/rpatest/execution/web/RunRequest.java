package com.rpatest.execution.web;

/** @param startStepId если задан — прогон начинается с этого шага сценария, а не с корней DAG;
 *                     шаги "до" него (по которым обход не пройдёт) остаются в статусе PENDING. */
public record RunRequest(String triggeredBy, Long startStepId) {
}
