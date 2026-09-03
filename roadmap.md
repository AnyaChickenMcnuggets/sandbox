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

## Sprint 8 — Hardening — IN PROGRESS
- [ ] Добор покрытия JaCoCo до целевого порога (контроллеры, конфиг, edge cases retry/ошибок)
- [ ] Финализация `architecture.md`/`agents.md`
- [ ] Ревью и чистка кода

## Открытые риски
- Точный формат ответа `POST /api/Account` не описан в swagger — реализован авто-детект
  (JSON/строка), требует подтверждения на реальном стенде.
- Статус "успех/ошибка" транзакции очереди выводится из `lastEventType` элемента очереди —
  финальный маппинг событий уточняется по факту реальных данных оркестратора.
