package com.rpatest.execution.report;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ReportMessagesTest {

    private static void assertTranslated(String raw, String expected) {
        assertThat(ReportMessages.translate(raw)).isEqualTo(expected);
    }

    @Test
    void translatesTheAssignmentStatesOfTheStatusPoller() {
        assertTranslated("Assignment 'My_Job_12_3' running on robot 'robot-7' (started 2026-10-07T20:41:10) (attempt #3)",
                "Задание «My_Job_12_3» выполняется на роботе «robot-7» (старт 07.10.2026 20:41:10) (попытка 3)");
        assertTranslated("Assignment 'J_1_2' in the orchestrator project queue (enqueued 2026-10-07T20:41:10.123456),"
                        + " waiting for a free robot (attempt #1)",
                "Задание «J_1_2» в очереди проектов оркестратора (поставлено 07.10.2026 20:41:10), ожидает свободного робота (попытка 1)");
        assertTranslated("Assignment 'J' not found in the project queue nor among robot launches (attempt #2)",
                "Задание «J» не найдено ни в очереди проектов, ни среди запусков на роботах (попытка 2)");
        assertTranslated("Assignment 'J' finished on robot 'r': successfully", "Задание «J» завершилось на роботе «r»: успешно");
        assertTranslated("Assignment 'J' finished on robot 'r': with an error", "Задание «J» завершилось на роботе «r»: с ошибкой");
    }

    @Test
    void keepsTheRobotsOwnErrorTextAfterTheTranslatedFailure() {
        assertTranslated("Assignment failed on robot 'robot-9': Не удалось открыть приложение",
                "Задание завершилось с ошибкой на роботе «robot-9»: Не удалось открыть приложение");
        assertTranslated("Timed out waiting for assignment 'J' to finish. Last known state: running on robot 'r'"
                        + " (started 2026-10-07T20:41:10)",
                "Истекло время ожидания завершения задания «J». Последнее известное состояние: выполняется на роботе «r»"
                        + " (старт 07.10.2026 20:41:10)");
    }

    @Test
    void translatesTheQueueCheckProgressWithReadableCounts() {
        assertTranslated("Checking queue 'q' (attempt #2): total=2 SUCCESS=1 ERROR=1 ",
                "Проверка очереди «q» (попытка 2): всего 2 (Успешно: 1, Ошибка: 1)");
        assertTranslated("Queue check 'q' passed: total=3 SUCCESS=3 ", "Проверка очереди «q» пройдена: всего 3 (Успешно: 3)");
        assertTranslated("Queue 'q' (id=e738d039-2954-49a2-91ce-900b2ea946e9) found, starting the check. Expected: SUCCESS>=5 ERROR>=0 ",
                "Очередь «q» найдена, начинаю проверку. Ожидалось: Успешно: не менее 5, Ошибка: не менее 0");
        assertTranslated("Looking up/creating queue 'q' for the check", "Поиск или создание очереди «q» для проверки");
    }

    @Test
    void translatesTheQueueCheckFailuresKeepingExpectedAndActual() {
        assertTranslated("Queue check 'q' failed: all tracked transactions already have a final status that will not change."
                        + " Expected: SUCCESS>=5  - actual: total=1 SUCCESS=1 ",
                "Проверка очереди «q» не пройдена: все отслеживаемые транзакции уже получили конечный статус, который не изменится."
                        + " Ожидалось: Успешно: не менее 5. Получено: всего 1 (Успешно: 1)");
        assertTranslated("Queue check 'q' did not pass within the allotted time. Expected: (total >= 5) - actual: total=1 SUCCESS=1 ",
                "Проверка очереди «q» не пройдена за отведённое время. Ожидалось: всего: не менее 5. Получено: всего 1 (Успешно: 1)");
        assertTranslated("Queue check 'q' stopped early: all tracked transactions (4) already have a final status,"
                        + " further waiting is pointless",
                "Проверка очереди «q» прекращена досрочно: все отслеживаемые транзакции (4) уже получили конечный статус,"
                        + " дальнейшее ожидание бессмысленно");
        assertTranslated("Expected: (no specific count expectations)", "Ожидалось: конкретных ожиданий по количеству нет");
    }

    @Test
    void translatesTheQueueAndJobSteps() {
        assertTranslated("Transactions added: 2/3 (last naturalKey='k2')", "Добавлено транзакций: 2 из 3 (последняя naturalKey «k2»)");
        assertTranslated("Queue 'q' is ready, transactions added: 3", "Очередь «q» готова, добавлено транзакций: 3");
        assertTranslated("Creating assignment 'A_1_2' for project 'Payments'", "Создание задания «A_1_2» по проекту «Payments»");
        assertTranslated("Starting assignment 'A_1_2'", "Запуск задания «A_1_2»");
        assertTranslated("Setting assignment arguments 'A_1_2': [k1, k2]", "Установка аргументов задания «A_1_2»: [k1, k2]");
        assertTranslated("Project 'Payments' not found in the orchestrator", "Проект «Payments» не найден в оркестраторе");
        assertTranslated("Step 'My Job' has neither rpaProjectName nor rpaProjectId",
                "В шаге «My Job» не указан ни rpaProjectName, ни rpaProjectId");
    }

    @Test
    void keepsTheCauseChainAfterATranslatedWrapper() {
        assertTranslated("Failed to execute job step 'My Job' - I/O error on POST request for \"https://x\": Connection refused",
                "Не удалось выполнить шаг-задание «My Job» - I/O error on POST request for \"https://x\": Connection refused");
        assertTranslated("Failed to run queue check 'Check' - Orchestrator call failed: GET /Items - 500 [no body]",
                "Не удалось выполнить проверку очереди «Check» - Ошибка вызова оркестратора: GET /Items - 500 [no body]");
    }

    @Test
    void makesRunsRecordedBeforeTheTranslationReadable() {
        assertTranslated("Проверка очереди 'q' (попытка #2): всего=3 SUCCESS=3", "Проверка очереди «q» (попытка #2): всего 3 (Успешно: 3)");
        assertTranslated("Ожидалось: SUCCESS>=2 (всего >= 5) — фактически: всего=3 SUCCESS=2",
                "Ожидалось: Успешно: не менее 2, всего: не менее 5. Получено: всего 3 (Успешно: 2)");
        assertTranslated("Задание 'x_1_2' выполняется на роботе 'r' (начато 2026-10-07T20:41:10)",
                "Задание «x_1_2» выполняется на роботе «r» (начато 07.10.2026 20:41:10)");
        assertTranslated("Очередь 'q' (id=e738d039-2954-49a2-91ce-900b2ea946e9) найдена, начинаю проверку. Ожидается: SUCCESS>=5 ",
                "Очередь «q» найдена, начинаю проверку. Ожидалось: Успешно: не менее 5");
    }

    @Test
    void leavesUnknownTextAloneAndNeverBreaksOnSpecialCharacters() {
        assertTranslated("robot said it's broken, can't continue", "robot said it's broken, can't continue");
        assertTranslated("cost $1 and a back\\slash", "cost $1 and a back\\slash");
        assertTranslated("Assignment failed on robot 'r': price $5 \\1", "Задание завершилось с ошибкой на роботе «r»: price $5 \\1");
        assertThat(ReportMessages.translate(null)).isEmpty();
        assertThat(ReportMessages.translate("   ")).isEqualTo("   ");
    }
}
