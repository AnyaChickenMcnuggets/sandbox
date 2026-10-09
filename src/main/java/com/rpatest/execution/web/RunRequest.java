package com.rpatest.execution.web;

/** @param startStepId       если задан — прогон начинается с этого шага сценария, а не с корней DAG;
 *                           шаги "до" него (по которым обход не пройдёт) остаются в статусе PENDING.
 *                           {@code triggeredBy} сюда не входит — берётся из {@code Authentication}
 *                           (см. {@code RunController}), клиент не может подставить чужое имя.
 *  @param sendReportByMail {@code true} — по завершении прогона отправить тому, кто его запустил,
 *                           письмо с итогом (и ссылкой на отчёт). Не задано/{@code false} — не
 *                           отправлять. Если отправка не включена на сервере — {@code 400}. */
public record RunRequest(Long startStepId, Boolean sendReportByMail) {
}
