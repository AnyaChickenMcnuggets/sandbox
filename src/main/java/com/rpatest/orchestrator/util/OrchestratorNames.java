package com.rpatest.orchestrator.util;

/**
 * Оркестратор принимает в наименованиях (Assignment.name, ExchangeQueue.name) только латинские
 * буквы, цифры и подчёркивание — любой другой символ (пробелы, кириллица, `#`, `.` и т.п.)
 * приводит к отказу запроса. Используется везде, где имя формируется из пользовательского ввода
 * или генерируется системой для передачи в оркестратор.
 */
public final class OrchestratorNames {

    private OrchestratorNames() {
    }

    public static String sanitize(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replaceAll("[^A-Za-z0-9_]", "_");
    }
}
