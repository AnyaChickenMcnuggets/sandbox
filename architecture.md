# Архитектура

## Терминология

Оркестратор — **Primo RPA** (внутреннее имя API — `LTools WebApi`, см. [orc_swagger.json](orc_swagger.json)).
Прямых аналогов терминов UiPath нет, соответствие:

| Термин в требованиях | Сущность Primo RPA Orchestrator API | Наш домен |
|---|---|---|
| Проект | `RpaProjects` | ссылка по `rpaProjectId` |
| Job / Задание | `Assignments` | `ScenarioStep(type=JOB)` → `StepRun.orchestratorAssignmentId` |
| Аргументы задания | `RpaProjectVariables` (`/Assignment/{id}`) | `JobStepConfig.arguments` |
| Очередь транзакций | `ExchangeQueues` | `ScenarioStep(type=QUEUE)` → `StepRun.orchestratorQueueId` |
| Транзакция очереди | `ExchangeQueueItem` / `ExchangeQueueValueDto` | `QueueStepConfig.transactions` / `QueueItemResult` |
| Аутентификация | `POST /api/Account` ({userName, password}) → `{token}` | `TokenProvider` |

## Слои

```
Controller (scenario/web, execution/web)
        │  DTO ↔ Entity маппинг, валидация запроса
        ▼
Service (scenario/service, execution/engine)
        │  бизнес-логика, транзакции БД
        ▼
Port (orchestrator/*Port — интерфейсы)
        │
        ▼
Client (orchestrator/client — RestClient-реализации)
        │  HTTP к Primo RPA Orchestrator, retry/circuit breaker
        ▼
Primo RPA Orchestrator REST API
```

Сервисы уровня `execution`/`scenario` зависят только от портов (`AssignmentsPort`,
`ExchangeQueuesPort`, `RpaProjectVariablesPort`), а не от HTTP-деталей — это даёт возможность
подменять клиента моками в unit-тестах без поднятия HTTP-сервера.

## ER-схема БД

```
test_scenario 1───* scenario_step 1───* scenario_step_edge (from_step_id, to_step_id) *───1 scenario_step
      │
      1
      │
      *
scenario_run 1───* step_run 1───* queue_item_result
      (step_run.step_id → scenario_step.id, step_run.scenario_run_id → scenario_run.id)
```

- `scenario_step.config` (JSONB) хранит специфичные для типа шага параметры: для `JOB` —
  `rpaProjectId`, аргументы, количество роботов; для `QUEUE` — параметры очереди и список
  транзакций-шаблонов.
- `scenario_step_edge` реализует DAG: обычная цепочка — одно исходящее ребро на шаг; разветвление
  (fan-out) — несколько исходящих рёбер у одного шага (например, Job → Queue A + Queue B).
- `step_run` хранит id созданных в оркестраторе сущностей (`orchestrator_assignment_id`,
  `orchestrator_queue_id`), чтобы `cleanup` мог их удалить, а повторный запуск сценария не зависел
  от них (каждый прогон создаёт новые Assignment/ExchangeQueue).

## Выполнение сценария

1. `POST /api/v1/scenarios/{id}/run` создаёт `ScenarioRun(status=PENDING)` и асинхронно (`@Async`)
   запускает `ScenarioExecutionEngine.execute(run)`.
2. Движок находит корневые шаги DAG (без входящих рёбер) и исполняет их через `StepExecutor`
   (Strategy: `JobStepExecutor` / `QueueStepExecutor`).
3. `JobStepExecutor`: создаёт `Assignment` (`POST /api/Assignments`), выставляет аргументы
   (`PUT /api/RpaProjectVariables/Assignment/{id}`), стартует (`PUT /api/Assignments/{id}/Start`),
   передаёт управление `StatusPoller`, который с заданным интервалом (`orchestrator.polling.
   interval`) опрашивает `GET /api/Assignments/{id}` до терминального статуса
   (`Complete`/`Error`) либо таймаута.
4. `QueueStepExecutor`: создаёт `ExchangeQueue` (`POST /api/ExchangeQueues`), добавляет
   транзакции-шаблоны (`PUT /api/ExchangeQueues/{id}/Items/Add`).
5. По завершении шага движок находит исходящие рёбра и параллельно (`CompletableFuture.allOf`)
   запускает все дочерние шаги — это и есть поддержка "разветвления на две очереди после
   определённого задания".
6. Статус `ScenarioRun` — агрегат статусов всех `StepRun` (SUCCEEDED, если все SUCCEEDED; FAILED,
   если хоть один FAILED; иначе RUNNING). Внешний клиент узнаёт о прогрессе, поллингом
   `GET /api/v1/runs/{runId}` (наш API), что закрывает требование "контроль выполнения в реальном
   времени" без WebSocket.
7. `POST /api/v1/scenarios/{id}/cleanup` берёт `orchestrator_assignment_id`/`orchestrator_queue_id`
   последнего `ScenarioRun` и вызывает `DELETE` в оркестраторе для каждого.

## Аутентификация в оркестраторе

- `TokenProvider` — потокобезопасный holder текущего JWT + времени истечения (парсится из поля
  `exp` payload'а токена без проверки подписи — валидность подтверждает сам оркестратор).
- `OrchestratorAuthService.getToken()` возвращает валидный токен, логинясь заново, если токена нет
  или он истёк (double-checked locking).
- `AuthorizationInterceptor` (RestClient interceptor) добавляет заголовок `Authorization: Bearer
  <token>` ко всем запросам к оркестратору; при ответе `401` — один раз форсирует релогин и
  повторяет запрос.
- Тело запроса — только `{userName, password}` (подтверждено на реальном стенде: `robotEdition`/
  `refreshToken` из схемы `LoginDto` в swagger не требуются), ответ — `{"token": "<jwt>"}`.
- Логин/пароль читаются из `application.yml` (`orchestrator.credentials.*`), значения
  зашифрованы Jasypt (`ENC(...)`), реальные секреты передаются через переменные окружения
  (`JASYPT_ENCRYPTOR_PASSWORD` — пароль шифрования, не хранится в репозитории). Токен и пароль
  никогда не пишутся в БД и не логируются (см. `logback`-маскирование в `agents.md`).

## Отказоустойчивость

- Resilience4j `@Retry` (экспоненциальный backoff, 3 попытки) на все `GET`-вызовы клиентов
  оркестратора; на `POST/PUT/DELETE` retry применяется только к сетевым ошибкам/5xx (не к 4xx —
  избегаем дублирующего создания сущностей).
- `@CircuitBreaker` на уровне `orchestrator/client/*` — при серии сбоев прекращает попытки на
  окно ожидания и отдаёт `OrchestratorApiException` с понятным сообщением.
- Единый `@ControllerAdvice` (`execution/web` и `scenario/web`) транслирует доменные исключения в
  HTTP-статусы (`404` — не найдено, `409` — конфликт состояния DAG/запуска, `502` —
  `OrchestratorApiException`).

## Конфигурация (`application.yml`)

| Свойство | Назначение |
|---|---|
| `orchestrator.base-url` | базовый URL Primo RPA Orchestrator |
| `orchestrator.credentials.username/password` | учётные данные (Jasypt `ENC(...)`) |
| `orchestrator.credentials.robot-edition` | значение `robotEdition` в `LoginDto` |
| `orchestrator.polling.interval` / `orchestrator.polling.timeout` | параметры опроса статуса Assignment |
| `orchestrator.http.connect-timeout` / `read-timeout` | таймауты HTTP-клиента |
| `orchestrator.tls.trusted-certificates` | пути к сертификатам CA оркестратора (`file:...`), если он за внутренним CA — иначе PKIX path building failed |
| `resilience4j.retry.instances.orchestrator.*` | политика retry |
| `resilience4j.circuitbreaker.instances.orchestrator.*` | политика circuit breaker |

## Тестирование

- Unit (Mockito) — сервисы и движок исполнения на моках портов.
- Контрактные (WireMock) — клиенты оркестратора против застабленных ответов, повторяющих схемы
  swagger.
- Repository/Integration (Testcontainers PostgreSQL + Flyway) — реальные SQL-миграции и запросы.
- Web (MockMvc) — контроллеры и `@ControllerAdvice`.
- JaCoCo — порог покрытия строк проверяется в `mvn verify` (см. `pom.xml`).
