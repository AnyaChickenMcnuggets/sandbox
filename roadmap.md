# Roadmap

Backend для автоматизации тестирования заданий (Assignments) и очередей (ExchangeQueues) на
оркестраторе Primo RPA. Термины см. в [architecture.md](architecture.md#терминология).

Статусы: `TODO` / `IN PROGRESS` / `DONE`.

## Sprint 0 — Bootstrap — DONE
- [x] Maven-проект (Java 17, Spring Boot 3.2), `pom.xml` с зависимостями (web, data-jpa, flyway,
      resilience4j, springdoc, jasypt, testcontainers, wiremock, jacoco)
- [x] `application.yml` (профили `default`/`test`), конфигурация подключения к Postgres и к
      оркестратору
- [x] Базовая Flyway-миграция `V1__init.sql`
- [x] `roadmap.md`, `agents.md`, `architecture.md`

## Sprint 1 — Auth & HTTP-клиент оркестратора — DONE
- [x] `OrchestratorProperties` (baseUrl, credentials, timeouts)
- [x] DTO: `LoginDto`, `RobotEdition`
- [x] `TokenProvider` (потокобезопасное хранение JWT + TTL из `exp`)
- [x] `OrchestratorAuthClient`/`OrchestratorAuthService` (`POST /api/Account`), парсинг ответа
      (JSON `{token}` ИЛИ голая строка — авто-детект)
- [x] `RestClient` с интерцептором `Authorization: Bearer`, обработка 401 → повторный логин 1 раз
- [x] Resilience4j retry/circuit breaker конфигурация для клиентов оркестратора
- [x] Unit + WireMock-тесты

## Sprint 2 — Jobs (Assignments) — DONE
- [x] DTO: `AssignmentCreateDto`, `AssignmentDto`, `AssignmentStatus`,
      `RpaProjectVariableEditByIdDto`, `RpaProjectVariableDto`
- [x] `AssignmentsPort` + `AssignmentsClient` (create/get/start/stop/delete)
- [x] `RpaProjectVariablesPort` + `RpaProjectVariablesClient` (get/update аргументов задания)
- [x] Unit + WireMock-тесты

## Sprint 3 — Queues (ExchangeQueues) — DONE
- [x] DTO: `ExchangeQueueCreateDto`, `ExchangeQueueDto`, `ExchangeQueueItemDto`,
      `ExchangeQueueValueDto`, `PageDto`
- [x] `ExchangeQueuesPort` + `ExchangeQueuesClient` (create/delete/addItem/listItems)
- [x] Unit + WireMock-тесты

## Sprint 4 — Домен сценариев — DONE
- [x] Flyway `V2__scenario.sql`: `test_scenario`, `scenario_step`, `scenario_step_edge`
- [x] JPA-сущности + репозитории
- [x] `ScenarioService` (CRUD, валидация DAG: без циклов, единственный корень)
- [x] `ScenarioController` + DTO + `@ControllerAdvice`
- [x] Repository (Testcontainers) + Web (MockMvc) тесты

## Sprint 5 — Движок исполнения (последовательный) — DONE
- [x] Flyway `V3__execution.sql`: `scenario_run`, `step_run`, `queue_item_result`
- [x] `StepExecutor` (Strategy) + `JobStepExecutor` + `QueueStepExecutor`
- [x] `ScenarioExecutionEngine` (обход DAG, последовательные цепочки)
- [x] `StatusPoller` (scheduled polling статуса Assignment)
- [x] `RunController` (`run`/`status`/`stop`)
- [x] Unit-тесты движка с моками портов оркестратора

## Sprint 6 — Параллельные ветки — DONE
- [x] Fan-out по `scenario_step_edge` через `@Async` + `CompletableFuture.allOf`
- [x] Агрегация статуса `ScenarioRun` из статусов параллельных `StepRun`
- [x] Тесты сценария с разветвлением (job → 2 queue параллельно)

## Sprint 7 — Cleanup и аудит очередей — DONE
- [x] `POST /api/v1/scenarios/{id}/cleanup` — удаление Assignments/ExchangeQueues последнего прогона
- [x] `GET /api/v1/runs/{runId}/steps/{stepId}/queue-items` — аудит очереди
- [x] End-to-end тест "job → queue → job"

## Sprint 8 — Hardening — DONE
- [x] Добор покрытия тестами: `JobStepExecutor`, `QueueStepExecutor`, `StatusPoller`,
      `ExecutionService`, `CleanupService`, `QueueAuditService`, `RpaProjectVariablesClient`,
      недостающие ветки `ExchangeQueuesClient`/`RunController`/`GlobalExceptionHandler`,
      `OrchestratorTrustStoreFactory`, `ScenarioStepEdgeId`. Итог: 98 тестов,
      **87.8%** instruction coverage, `mvn verify` (JaCoCo-порог 80%) — зелёный.
- [x] Реальный end-to-end прогон против стенда пользователя подтверждён (job → SUCCEEDED)
- [x] Финализация `architecture.md`/`agents.md` по итогам реальных находок (эндпоинты v2,
      санитайзинг имён, LocalDateTime вместо OffsetDateTime, фолбэк по имени при пустом ответе POST)

## Открытые риски
- ~~Точный формат ответа `POST /api/Account`~~ — подтверждено: запрос `{userName, password}`,
  ответ `{"token": "<jwt>"}`. `LoginDto` упрощён под это (без `robotEdition`/`refreshToken`).
- ~~`Start` мог задублироваться при retry после сетевого таймаута~~ — исправлено: `@Retry` убран
  с `AssignmentsClient.start()`/`stop()` (неидемпотентные операции; оркестратор жёстко запрещает
  второй `Start` того же `rpaProjectId` — "запрещены повторы в очереди ожидания", `501`). Retry на
  `create`/`get`/`list`/`delete` оставлен — эти операции безопаснее дублировать.
- **Один проект = один активный запуск** — жёсткое ограничение оркестратора (`RpaProjectQueue`):
  нельзя стартовать второй `Assignment` того же `rpaProjectId`, пока первый не завершится. Наш
  движок это не проверяет заранее — просто получит `501` от оркестратора и корректно пометит шаг
  `FAILED` (это ок), но если сценарии могут теоретически стартовать параллельно с одним и тем же
  проектом (два прогона одного сценария, две ветки на один проект) — стоит решить, добавлять ли
  проверку "проект уже занят" на нашей стороне заранее, с понятным сообщением, вместо ожидания
  ошибки от оркестратора. См. `TESTING.md` разделы 2/5/7.
- Статус "успех/ошибка" транзакции очереди выводится из `lastEventType` элемента очереди —
  финальный маппинг событий уточняется по факту реальных данных оркестратора.
- `POST /api/ExchangeQueues` подтверждён пользователем как рабочий, но поведение при повторном
  создании очереди с уже существующим именем (при повторном запуске того же сценария без cleanup)
  не проверено — см. `TESTING.md` раздел 9. Если окажется, что оркестратор отвечает ошибкой
  конфликта — `QueueStepExecutor.create` нужно будет сделать идемпотентным (`findByName` перед
  `create`, пропускать создание, если очередь уже есть).
- `StatusPoller`/`ExecutionService.stopRun` не считают `PAUSED` терминальным статусом: после ручной
  остановки задания (`PUT /Assignments/{id}/Stop`) поллер в других (ещё не остановленных) шагах
  продолжит ждать `Complete`/`Error` до таймаута — не критично, но стоит уточнить у оркестратора,
  в какой статус реально переходит остановленное задание.
- `ScenarioStepRepositoryIT` (Testcontainers) не запускался в этой сессии — в текущем окружении
  нет Docker. Прогнать в CI/локально с Docker перед мёржем.

## Backlog (за рамками текущего скоупа)
- Аутентификация/авторизация собственного REST API (осознанно не делали на этом этапе).
- Строгая проверка несовпавших ключей в `JobStepConfig.arguments` (сейчас молча игнорируются).
- UI/дашборд поверх REST API для визуального конструирования сценариев и просмотра прогонов.
