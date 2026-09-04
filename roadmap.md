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
- [x] DTO: `LoginDto` (`{userName, password}`)
- [x] `TokenProvider` (потокобезопасное хранение JWT + TTL из `exp`)
- [x] `OrchestratorAuthService` (`POST /api/Account`), парсинг ответа
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

## Sprint 9 — Проверка фактического результата выполнения — DONE
Уточнение от пользователя: `AssignmentStatus.COMPLETE` означает только "оркестратор поставил
проект в очередь выполнения", а не "робот реально доделал работу". Единственный надёжный сигнал
завершения — статус транзакций в очереди, которую читает/пишет проект.
- [x] `naturalKey` добавлен в `ExchangeQueueValueDto` (отсутствовал — не могли сопоставлять
      результаты с отправленными транзакциями)
- [x] `QueueItemDerivedStatus` (NEW/IN_PROGRESS/SUCCESS/ERROR/BUSINESS_ERROR) — выводится из
      `readedRobotAt`/`lastEventType`, поскольку отдельного поля статуса в API нет
- [x] Новый тип шага `QUEUE_CHECK` (`Flyway V4__queue_check_step.sql`) — поллит существующую
      очередь до совпадения фактических количеств по статусу (`expectedStatusCounts`) и/или
      минимального общего числа элементов (`minTotalCount`) с ожиданиями автора сценария,
      опционально отфильтрованных по `naturalKeys`; таймаут — явная ошибка шага с деталями
      "ожидалось X, фактически Y". Типичное место — сразу после `JOB`, перед следующим `JOB`
- [x] `QueueCheckStepExecutor`, конфиг `orchestrator.queue-check-polling.*`, unit-тесты
- [x] `QueueAuditService`/`QueueItemResponse` возвращают `naturalKey` и производный статус

## Sprint 10 — Реальное отслеживание выполнения + идемпотентные очереди — DONE
Уточнение от пользователя: (1) `QUEUE_CHECK` после `JOB` — это опциональная бизнес-проверка,
а не единственный способ узнать, что задание реально выполнилось: сам `JobStepExecutor` обязан
отслеживать реальный запуск на роботе, даже если после него в сценарии вообще нет `QUEUE_CHECK`.
(2) Создание очереди/транзакций должно быть идемпотентным ("используй существующую, если есть") —
`Assignment` при этом всегда создаётся заново и удаляется после прогона.
- [x] `RpaProjectLaunchDto`/`QueueItemProjectDto`/`ListResultDto<T>`, `RpaProjectLaunchesPort` +
      `RpaProjectLaunchesClient` (`GET /api/RpaProjectLaunches/assignment/{assignmentId}`),
      `RpaProjectQueuePort` + `RpaProjectQueueClient` (`GET /api/RpaProjectQueue?AssignmentId=`)
- [x] `StatusPoller` переписан: источник истины — `RpaProjectLaunches` (реальный запуск на
      роботе, `completedAt`/`killedAt`/`success`), а не `AssignmentStatus`. Диагностика таймаута
      различает "всё ещё в очереди проектов" / "выполняется на роботе X, не завершилось" /
      "не найдено нигде"
- [x] `JobStepExecutor` берёт текст ошибки из `RpaProjectQueue.errorMsg` при `success=false`
- [x] `ExchangeQueueProvisioner.ensureExists` (find-or-create) — общий паттерн, теперь и
      `QueueStepExecutor`, и `QueueCheckStepExecutor` используют существующую очередь вместо
      падения на конфликте имени или ошибки "не найдена"
- [x] 121 тест (было 108), `mvn verify` (JaCoCo) — зелёный

## Sprint 11 — Починка чтения элементов очереди — DONE
На реальном стенде `GET /api/ExchangeQueues/{id}/Items` (без версии) не падал с ошибкой, а тихо
отдавал не тот формат ответа — из-за этого `QUEUE_CHECK` не видел элементов вообще и просто
бесконечно поллил до таймаута, а прогон сценария завис в `RUNNING`. Подтверждено рабочим Python-
клиентом `orc_worker.py`: реальный список элементов отдаёт только `v2`-эндпоинт, и в форме
`{totalCount, filterCount, result}` (как `RpaProjectLaunches`), а не `{totalCount, items}`,
которую мы ошибочно предполагали.
- [x] `ExchangeQueuesClient.listItems`: `GET /api/ExchangeQueues/{id}/Items` → `GET
      /api/ExchangeQueues/v2/{id}/Items`, тип ответа `PageDto` → `ListResultDto` (`.items()` →
      `.result()`); `PageDto` удалён как класс, основанный на неверном предположении
- [x] `QueueAuditService`, `QueueCheckStepExecutor` переведены на `.result()`
- [x] Тест на регрессию: `listItemsDoesNotHitTheStaleV1Endpoint` — явно проверяет, что v1-путь не
      вызывается вообще
- [x] 122 теста, `mvn verify` (JaCoCo) — зелёный

## Sprint 12 — Указание проекта по имени — DONE
Пользователь: не хочет искать `rpaProjectId` вручную — указывает название проекта, бэкенд сам
ищет id по списку проектов.
- [x] `RpaProjectShortDto`, `RpaProjectsPort` + `RpaProjectsClient` (`GET
      /api/RpaProjects/v3/short` — тот же эндпоинт, что и в `OrcService.java`), `findByName` с
      точным совпадением, предпочитает `active=true` при дублях (разные версии одного имени)
- [x] `JobStepConfig.rpaProjectName` (опционально, наряду с `rpaProjectId` — ровно одно из двух);
      `JobStepExecutor` резолвит id до создания Assignment, явная ошибка если имя не найдено или
      не задано ни имя, ни id
- [x] 128 тестов, `mvn verify` (JaCoCo) — зелёный

## Sprint 13 — Наблюдаемость выполнения (live-прогресс + логи) — DONE
Пользователь после реального прогона: задание отработало на роботе и в оркестраторе всё видно, но
статус рана всё ещё `RUNNING` без деталей, самих `QUEUE_CHECK`-шагов не видно вообще, а по логам
сервера не восстановить, что происходит — "лучше будет много логов, чем 0".
- [x] Flyway `V5__step_run_detail.sql`: `step_run.detail` (TEXT) + `detail_updated_at`
      (TIMESTAMPTZ)
- [x] `StepProgressReporter` — единая точка для одновременного обновления `StepRun.detail` в БД и
      INFO-лога той же строки; подключён в `JobStepExecutor`, `QueueStepExecutor`,
      `QueueCheckStepExecutor`, `StatusPoller` на каждый значимый переход/итерацию поллинга
- [x] `StepRunResponse` расширен: `stepName`, `stepType`, `detail`, `detailUpdatedAt` —
      `ExecutionService.toResponse` присоединяет `StepRun` к `ScenarioStep` через
      `ScenarioStepRepository.findAllById`
- [x] `logging.level.com.rpatest: INFO` в `application.yml` + `log.info`/`log.warn`/`log.error` по
      всему `ScenarioExecutionEngine` и исполнителям шагов (старт/финиш рана, начало/итог каждого
      шага, следующие шаги DAG)
- [x] 129 тестов (было 128, +`StepProgressReporterTest`), `mvn verify` (JaCoCo) — зелёный

## Sprint 14 — Фикс дедлока движка на длинных цепочках DAG — DONE
Пользователь после Sprint 13: логи обрывались сразу после "шаг 'Job' запускает следующие шаги:
[Check Input Queue Result]" и дальше ничего не происходило, `QUEUE_CHECK`-шаги не появлялись в
`GET /api/v1/runs/{runId}` вообще, статус навсегда оставался `RUNNING`. Причина — не пропавшая
фича, а дедлок движка: `executeStepRecursively` рекурсивно вызывал
`CompletableFuture.runAsync(...).join()`, и каждый уровень цепочки навсегда занимал отдельный поток
пула `scenarioExecutionExecutor` (`corePoolSize=4`) в ожидании следующего. `ThreadPoolExecutor`
создаёт потоки сверх `corePoolSize` только когда очередь заполнена (`queueCapacity=100` — почти
никогда), а не когда все core-потоки заняты/заблокированы — поэтому на цепочке
`queueIn→queueOut→job→checkInput→checkOutput` (5 уровней + сам `runScenario` на потоке
`ExecutionService`) 4-й уровень (`job`, последний core-поток) успешно доходил до конца и пытался
запустить `checkInput`, но все core-потоки к этому моменту уже были заблокированы в `.join()` друг
на друге — задача просто вставала в очередь без исполнителя. Оттуда и молчание в логах, и
отсутствие `StepRun` для `checkInput`/`checkOutput` (он создаётся в начале
`executeStepRecursively`, которая для них так и не запустилась).
- [x] `ScenarioExecutionEngine.executeStepRecursively` заменён на `executeStepAsync` +
      `runStep`: вместо рекурсивного блокирующего `runAsync(...).join()` — цепочка
      `supplyAsync(...).thenComposeAsync(...)`, которая не занимает поток пула на ожидание детей
      (продолжение планируется на пуле по готовности родителя, а не блокирует чей-то поток)
- [x] Регрессионный тест `doesNotDeadlockOnLinearChainLongerThanExecutorThreadCount` —
      реальный `Executors.newFixedThreadPool(2)` + линейная цепочка из 6 шагов,
      `assertTimeoutPreemptively(10s)`: на старом коде гарантированно виснет уже на 3-м уровне, на
      новом укладывается в единицы миллисекунд
- [x] 130 тестов (было 129), `mvn verify` (JaCoCo) — зелёный

## Sprint 15 — Полная топология шагов видна с начала прогона — DONE
Пользователь после Sprint 14 (дедлок исправлен, но): даже пока `job` ещё в `RUNNING`, шагов
`QUEUE_CHECK` (`checkInput`/`checkOutput`) вообще нет в `steps[]` ответа `GET
/api/v1/runs/{runId}` — непонятно, есть ли они в сценарии вообще. Причина: `StepRun` заводился
только в момент, когда обход DAG реально доходил до шага — то есть шаг, до которого очередь ещё не
дошла, был неотличим от "такого шага в сценарии нет".
- [x] `ScenarioExecutionEngine.runScenario` теперь создаёт `StepRun(PENDING)` на все шаги сценария
      сразу, до начала обхода DAG (`stepRunRepository.saveAll(...)`)
- [x] `runStep` ищет уже существующую (пре-созданную) строку через новый метод
      `StepRunRepository.findByScenarioRunIdAndStepId` вместо создания новой — так шаг, который
      начал выполняться, обновляет ту же строку, что была видна как `PENDING`
- [x] Тест `preCreatesPendingStepRunsForNotYetReachedStepsSoFullTopologyIsVisibleImmediately` —
      напрямую воспроизводит жалобу пользователя: реальный пул потоков + блокирующий `JOB`-шаг,
      проверка, что соседний ещё не начавшийся `QUEUE_CHECK` уже виден как `PENDING`, пока `JOB`
      выполняется
- [x] `skipsChildStepsWhenParentFails` обновлён под новое поведение — пропущенный шаг остаётся
      `PENDING` (раньше для него вообще не было строки)
- [x] 132 теста (было 130), `mvn verify` (JaCoCo) — зелёный

## Sprint 16 — Удалённые транзакции и порядок шагов в ответе — DONE
Два замечания пользователя после Sprint 15: (1) `QUEUE_CHECK` должен игнорировать удалённые
транзакции при подсчёте статусов; (2) `steps[]` в `GET /api/v1/runs/{runId}` выдаётся вперемешку,
а не в порядке выполнения сценария.
- [x] `QueueCheckStepExecutor.fetchMatchingItems` фильтрует `ExchangeQueueValueDto.deletedAt !=
      null` до подсчёта количеств по статусу — удалённый элемент больше не искажает
      `expectedStatusCounts`/`minTotalCount`. `QueueAuditService` (ручной аудит) не тронут —
      удалённые транзакции там по-прежнему видны, это осознанно (полезно для отладки)
- [x] `ExecutionService.toResponse` сортирует `steps[]` по `ScenarioStep.position` вместо порядка
      возврата `StepRunRepository.findByScenarioRunId` (тот ничего не гарантирует про порядок,
      особенно после Sprint 15 — все `StepRun` заводятся одним `saveAll`)
- [x] Тесты: `excludesDeletedTransactionsFromCounts` (`QueueCheckStepExecutorTest`),
      `getRunOrdersStepsByScenarioPositionRegardlessOfRepositoryReturnOrder`
      (`ExecutionServiceTest`)
- [x] 134 теста (было 132), `mvn verify` (JaCoCo) — зелёный

## Открытые риски
- ~~Точный формат ответа `POST /api/Account`~~ — подтверждено: запрос `{userName, password}`,
  ответ `{"token": "<jwt>"}`. `LoginDto` упрощён под это (без `robotEdition`/`refreshToken`).
- ~~`Start` мог задублироваться при retry после сетевого таймаута~~ — исправлено: `@Retry` убран
  с `AssignmentsClient.start()`/`stop()` (неидемпотентные операции; оркестратор жёстко запрещает
  второй `Start` того же `rpaProjectId` — "запрещены повторы в очереди ожидания", `501`). Retry на
  `create`/`get`/`list`/`delete` оставлен — эти операции безопаснее дублировать.
- ~~Статус "успех/ошибка" транзакции очереди~~ — решено через `QueueItemDerivedStatus` +
  `QUEUE_CHECK` (Sprint 9).
- **Один проект = один активный запуск** — жёсткое ограничение оркестратора (`RpaProjectQueue`):
  нельзя стартовать второй `Assignment` того же `rpaProjectId`, пока первый не завершится. Наш
  движок это не проверяет заранее — просто получит `501` от оркестратора и корректно пометит шаг
  `FAILED` (это ок), но если сценарии могут теоретически стартовать параллельно с одним и тем же
  проектом (два прогона одного сценария, две ветки на один проект) — стоит решить, добавлять ли
  проверку "проект уже занят" на нашей стороне заранее, с понятным сообщением, вместо ожидания
  ошибки от оркестратора. См. `TESTING.md` разделы 2/5/7.
- ~~`POST /api/ExchangeQueues` на уже существующее имя (повторный прогон без cleanup)~~ — больше
  не актуально: `ExchangeQueueProvisioner` (Sprint 10) делает создание очереди идемпотентным
  (find-or-create), `POST` теперь вызывается только когда очереди действительно ещё нет.
- ~~Диагностика ошибок оркестратора без URL/метода вызова~~ — исправлено: `ScenarioExecutionEngine`
  теперь дописывает в `StepRun.errorMessage` всю цепочку причин (`describeWithCauses`), а не только
  верхний текст-обёртку.
- `ExecutionService.stopRun` помечает `StepRun` `FAILED` немедленно и напрямую (не дожидаясь
  `StatusPoller`), а поток `JobStepExecutor`, всё ещё блокированный в `pollUntilTerminal`, узнаёт
  об остановке только когда (и если) в `RpaProjectLaunches` появится запись с `killedAt` — до этого
  момента, а в худшем случае до собственного таймаута, он продолжает опрашивать. Гонка за запись в
  одну и ту же строку `step_run` (HTTP-поток `/stop` и async-поток движка) не проверялась на
  реальном стенде — не проверено, действительно ли `PUT /Assignments/{id}/Stop` быстро приводит к
  `killedAt` в `RpaProjectLaunches`.
- **Расхождение в эндпоинте добавления транзакции.** `QueueStepExecutor.enqueue` использует `PUT
  /api/ExchangeQueues/v2/enqueue/{queueName}` (по имени очереди), а рабочий Python-клиент
  `orc_worker.py` (`add_transaction`) — `PUT /api/ExchangeQueues/{id}/Items/Add` (по id очереди,
  без версии). Пользователь пока не сообщал о проблемах с этим эндпоинтом (задание `job1` доходит
  до `SUCCEEDED`), но после починки чтения (Sprint 11) стоит явно перепроверить на реальном
  стенде, что отправленные нами транзакции корректно видны через `checkInput`/аудит — если оно уже
  тихо не работает (как было с чтением), тот же паттерн диагностики (сверка с `orc_worker.py`)
  применим и здесь.
- `ScenarioStepRepositoryIT` (Testcontainers) не запускался в этой сессии — в текущем окружении
  нет Docker. Прогнать в CI/локально с Docker перед мёржем.
- ~~В `TESTING.md` очередь-приёмник результата (`queueOut`) была потомком `job1`, хотя её текст
  утверждал обратное~~ — исправлено: правильная форма `queueIn → queueOut → job → checkInput →
  checkOutput` (обе очереди — предки задания). См. правило в `architecture.md`.
- Движок не поддерживает fan-in (несколько родителей у одного шага DAG) — узел с двумя входящими
  рёбрами будет исполнен дважды параллельно вместо одного раза после обоих родителей. Пока не
  требовалось (текущие сценарии обходятся линейными цепочками и fan-out), но если понадобится
  дождаться нескольких независимых веток перед одним шагом — нужна доработка
  `ScenarioExecutionEngine` (подсчёт завершённых входящих рёбер).
- Диагностика ошибок оркестратора: `OrchestratorApiException`/`ErrorResponse` сейчас передают
  только текст исключения (например, "500 [no body]" от `RestClientException`) без URL/метода
  вызова, из-за которого не всегда сразу понятно, какой именно HTTP-вызов упал — стоит рассмотреть
  добавление метода+URI в сообщение об ошибке `OrchestratorClientSupport`.

## Backlog (за рамками текущего скоупа)
- Аутентификация/авторизация собственного REST API (осознанно не делали на этом этапе).
- Строгая проверка несовпавших ключей в `JobStepConfig.arguments` (сейчас молча игнорируются).
- UI/дашборд поверх REST API для визуального конструирования сценариев и просмотра прогонов.
- Поддержка fan-in в `ScenarioExecutionEngine` (см. выше).
