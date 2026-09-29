package com.rpatest.execution.web;

/** @param startStepId если задан — прогон начинается с этого шага сценария, а не с корней DAG;
 *                     шаги "до" него (по которым обход не пройдёт) остаются в статусе PENDING.
 *                     {@code triggeredBy} сюда не входит — берётся из {@code Authentication}
 *                     (см. {@code RunController}), клиент не может подставить чужое имя. */
public record RunRequest(Long startStepId) {
}
