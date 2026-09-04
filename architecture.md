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
| Статус транзакции очереди | нет отдельного поля — выводится из `readedRobotAt`/`lastEventType` | `QueueItemDerivedStatus` (NEW/IN_PROGRESS/SUCCESS/ERROR/BUSINESS_ERROR) |
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
  `rpaProjectId`, аргументы; для `QUEUE` — параметры очереди и список транзакций-шаблонов; для
  `QUEUE_CHECK` — имя проверяемой очереди, опциональный фильтр по `naturalKey` и ожидаемые
  количества по статусу/минимальный общий счётчик (`QueueCheckStepConfig`).
- `scenario_step_edge` реализует DAG: обычная цепочка — одно исходящее ребро на шаг; разветвление
  (fan-out) — несколько исходящих рёбер у одного шага (например, Job → Queue A + Queue B).
- `step_run` хранит id созданных в оркестраторе сущностей (`orchestrator_assignment_id`,
  `orchestrator_queue_id`), чтобы `cleanup` мог их удалить, а повторный запуск сценария не зависел
  от них (каждый прогон создаёт новые Assignment/ExchangeQueue).

## Выполнение сценария

1. `POST /api/v1/scenarios/{id}/run` создаёт `ScenarioRun(status=PENDING)` и асинхронно (`@Async`)
   запускает `ScenarioExecutionEngine.execute(run)`.
2. Движок находит корневые шаги DAG (без входящих рёбер) и исполняет их через `StepExecutor`
   (Strategy: `JobStepExecutor` / `QueueStepExecutor` / `QueueCheckStepExecutor`).
3. `JobStepExecutor`: создаёт `Assignment` (`POST /api/Assignments/v2` — эндпоинт v1 без версии в
   swagger присутствует, но не актуален на реальном стенде), выставляет аргументы
   (`PUT /api/RpaProjectVariables/Assignment/{id}`), стартует (`PUT /api/Assignments/{id}/Start`),
   передаёт управление `StatusPoller`, который с заданным интервалом (`orchestrator.polling.
   interval`) опрашивает `GET /api/Assignments/v2/{id}` до терминального статуса
   (`Complete`/`Error`) либо таймаута.
   **Важно:** `AssignmentStatus.COMPLETE` отражает только то, что оркестратор успешно поставил
   проект в очередь на выполнение (`RpaProjectQueue`) — не то, что робот реально доделал работу.
   Единственный надёжный способ узнать, что данные обработаны, — проверить транзакции очереди,
   которую читает/пишет проект, поэтому фактическая проверка результата выносится в отдельный шаг
   `QUEUE_CHECK` (см. п. 4а), а не встраивается в `JobStepExecutor`.
4. `QueueStepExecutor`: создаёт `ExchangeQueue` (`POST /api/ExchangeQueues`), добавляет
   транзакции-шаблоны (`PUT /api/ExchangeQueues/{id}/Items/Add`) — используется и для входной
   очереди (данные для задания) и для выходной (создаётся заранее, до старта задания, чтобы
   заданию было куда писать; своих транзакций может не добавлять).
4a. `QueueCheckStepExecutor` (тип шага `QUEUE_CHECK`): не создаёт очередь, а поллит уже
   существующую (`GET /api/ExchangeQueues/{id}/Items`, постранично) до тех пор, пока фактические
   количества элементов по производному статусу (`QueueItemDerivedStatus`, опционально
   отфильтрованные по списку `naturalKey`) не совпадут с ожидаемыми из `config.expectedStatusCounts`
   / `config.minTotalCount`, либо не истечёт `orchestrator.queue-check-polling.timeout`. Фильтр по
   `naturalKeys` работает как точное совпадение по умолчанию (для входной очереди — мы сами знаем
   точные ключи отправленных транзакций) либо как совпадение по префиксу при
   `config.naturalKeyPrefixMatch=true` (для выходной очереди — базовый ключ сквозной от входа к
   выходу, но на выходе к нему может дописываться суффикс для трассировки при разветвлении одной
   входной транзакции на несколько выходных). Это и есть
   автоматическая проверка результата — сценарий явно падает (`StepExecutionException` с текстом
   "ожидалось X, фактически Y"), если распределение по статусам не совпало с ожиданиями автора
   сценария. Обычно ставится сразу после `JOB`-шага, перед следующим `JOB`.
5. По завершении шага движок находит исходящие рёбра и параллельно (`CompletableFuture.allOf`)
   запускает все дочерние шаги — это и есть поддержка "разветвления на две очереди после
   определённого задания".
6. Статус `ScenarioRun` — агрегат статусов всех `StepRun` (SUCCEEDED, если все SUCCEEDED; FAILED,
   если хоть один FAILED; иначе RUNNING). Внешний клиент узнаёт о прогрессе, поллингом
   `GET /api/v1/runs/{runId}` (наш API), что закрывает требование "контроль выполнения в реальном
   времени" без WebSocket.
7. `POST /api/v1/scenarios/{id}/cleanup` берёт `orchestrator_assignment_id`/`orchestrator_queue_id`
   последнего `ScenarioRun` и вызывает `DELETE` в оркестраторе для каждого.

### Правило проектирования DAG: очереди — предки задания, не потомки

Движок ничего не знает про то, какую очередь читает/пишет конкретный `JOB` (это скрыто внутри
проекта в Studio) — он просто исполняет DAG в порядке рёбер. Это значит, что **любая** `QUEUE`,
которую `JOB` должен прочитать или в которую должен записать результат, обязана быть предком этого
`JOB`-шага (создана раньше него), иначе на реальном стенде проект либо не найдёт входную очередь,
либо не сможет писать в ещё не существующую выходную. Частая ошибка (в том числе допущенная в
`TESTING.md` на раннем этапе) — поставить выходную очередь потомком задания, "чтобы заданию было
куда писать", что на самом деле создаёт очередь уже *после* того, как заданию она нужна. Правильная
форма — `queueIn → queueOut → job → checkInput → checkOutput` (обе очереди до задания, проверки
после); распараллеливать `queueIn`/`queueOut` между собой можно, а вот сводить два независимых шага
в один общий дочерний узел (fan-in) — нельзя: движок не дедуплицирует повторные заходы в узел с
несколькими родителями и выполнит его несколько раз (см. `agents.md`).

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
- Исключение — `AssignmentsClient.start()`/`stop()`: retry намеренно отключён. `Start` ставит
  проект в очередь запуска оркестратора; повтор запроса после сетевого таймаута (когда первая
  попытка на самом деле прошла) приводит к ошибке "запрещены повторы в очереди ожидания" —
  оркестратор не допускает второй активный запуск того же `rpaProjectId`. Лучше дать шагу упасть
  явно, чем рисковать дублирующим/сбивающим с толку вызовом неидемпотентной операции.
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
| `orchestrator.polling.interval` / `orchestrator.polling.timeout` | параметры опроса статуса Assignment |
| `orchestrator.queue-check-polling.interval` / `.timeout` | параметры опроса очереди в `QUEUE_CHECK` (по умолчанию для шагов без своих `pollIntervalSeconds`/`timeoutSeconds`) |
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
