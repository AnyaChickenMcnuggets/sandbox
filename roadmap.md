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

## Sprint 17 — Владение очередью при cleanup, повторы Error, запуск с этапа — DONE
Три замечания пользователя: (1) cleanup удалял очередь любого шага с `orchestratorQueueId`,
включая переиспользованные (не созданные этим прогоном) и очереди `QUEUE_CHECK` — нужно удалять
только реально созданные этим прогоном; (2) `QUEUE_CHECK` должен учитывать лимит повторов очереди
при подсчёте `ERROR`-транзакций — Error, для которого повторы ещё не исчерпаны, не финальный
результат; (3) нужна возможность запускать прогон с произвольного шага сценария, а не только с
корня DAG целиком.
- [x] `ExchangeQueueProvisioner.ensureExists` возвращает `Result(queue, created)` вместо голого DTO
      — вызывающий теперь знает, была ли очередь реально создана или переиспользована
- [x] `StepRun.orchestrator_queue_owned` (`V6__step_run_queue_owned.sql`) — `true` только когда
      `QueueStepExecutor` сам создал очередь (`Result.created()`); `QueueCheckStepExecutor` никогда
      не выставляет этот флаг, даже когда сам вызывает get-or-create
- [x] `CleanupService.cleanupLastRun` удаляет очередь только при
      `orchestratorQueueId != null && orchestratorQueueOwned` — Assignment по-прежнему удаляется
      всегда (он и раньше создавался заново каждый прогон)
- [x] `ExchangeQueueDto.maxRetray` и `ExchangeQueueValueDto.retray` — довешаны поля из ответа
      оркестратора (были в swagger, но не мапились): лимит повторов очереди и текущее число
      повторов конкретной транзакции
- [x] `QueueCheckStepExecutor.effectiveStatus` — `ERROR` с `item.retray() < queue.maxRetray()`
      считается `IN_PROGRESS`, а не `ERROR`, при подсчёте `expectedStatusCounts`/`minTotalCount`
      (оркестратор ещё повторит попытку); `ERROR` с исчерпанными повторами по-прежнему `ERROR`
- [x] `POST /api/v1/scenarios/{id}/run` принимает опциональный `startStepId` — прогон начинается с
      указанного шага вместо корней DAG; шаги "до" него остаются `PENDING` (обход их не касается),
      ответственность за то, что их предпосылки уже выполнены — на вызывающем.
      `ScenarioRun.start_step_id` (`V7__scenario_run_start_step.sql`) сохраняет и возвращает в
      `RunResponse`, с чего был запущен конкретный прогон
- [x] `StepRunResponse.orchestratorQueueOwned` — фронт теперь видит, какие шаги реально владеют
      своей очередью (будет удалена на cleanup), а какие нет
- [x] Тесты: `doesNotDeleteQueueThatWasReusedRatherThanCreated`,
      `doesNotDeleteQueueCreatedByQueueCheckStep` (`CleanupServiceTest`),
      `errorNotYetExhaustingQueueRetryLimitIsNotCountedAsFinalError`,
      `errorAtOrBeyondQueueRetryLimitCountsAsFinalError` (`QueueCheckStepExecutorTest`),
      `startStepIdSkipsAncestorsAndBeginsTraversalAtThatStep`,
      `runFailsWhenStartStepIdNotFoundInScenario` (`ScenarioExecutionEngineTest`),
      `startRunWithStartStepIdValidatesOwnershipAndPassesItToEngine`,
      `startRunThrowsWhenStartStepDoesNotBelongToScenario`,
      `startRunThrowsWhenStartStepMissing` (`ExecutionServiceTest`)
- [x] 142 теста (было 134), `mvn verify` (JaCoCo) — зелёный

## Sprint 18 — История прогонов переживает удаление сценария — DONE
Пользователь: на экране мониторинга фронт показывает название сценария, а `RunResponse` содержит
только `scenarioId` — приходится догружать отдельным `GET /api/v1/scenarios/{scenarioId}` (лишний
запрос, "мерцание" загрузки), и если сценарий с тех пор удалён, показать для старого прогона нечего.
Запросил денормализованное `scenarioName` в `RunResponse`, по аналогии с тем, как (по его
ожиданию) уже должен работать `stepName` в `StepRunResponse`.

При реализации выяснилось: `scenario_run.scenario_id` и `step_run.step_id` в схеме были `ON DELETE
CASCADE` — удаление сценария и без всякого нового поля уже стирало ВСЮ историю его прогонов из БД
целиком (не только имя), а `PUT /scenarios/{id}` (пересоздаёт `scenario_step` с нуля) стирал
`step_run` каждого предыдущего прогона того же сценария. То есть "прогон переживает удаление
сценария" было невозможно даже с денормализованным именем — самого прогона просто не оставалось.
Уточнил у пользователя — подтверждено: убрать `CASCADE`, сохранять историю прогонов.
- [x] `scenario_run.scenario_id`: `ON DELETE CASCADE` → `ON DELETE SET NULL`, столбец nullable
- [x] `step_run.step_id`: `ON DELETE CASCADE` → `ON DELETE SET NULL`, столбец nullable (иначе
      `scenario_run` пережил бы удаление, но со `steps: []` — сценарий и без того уже отдельно
      удаляет+пересоздаёт свои `scenario_step` на каждый `PUT`)
- [x] `scenario_run.scenario_name` (денормализация `TestScenario.name`, заполняется в
      `ExecutionService.startRun` в момент создания рана) — `RunResponse.scenarioName`
- [x] `step_run.step_name`/`step_type` (денормализация `ScenarioStep.name`/`type`, заполняется в
      `ScenarioExecutionEngine` при пре-создании `StepRun(PENDING)`) — `StepRunResponse.stepName`/
      `stepType` теперь читают эти денормализованные поля вместо live-join с `scenario_step`,
      который для удалённого шага вернул бы `null`
- [x] `ExecutionService.toResponse`: join с `scenario_step` оставлен только для сортировки по
      `position` (не для имени/типа); `findAllById` фильтрует `null` из `stepId` перед вызовом
      (иначе падает на orphaned `step_run`)
- [x] Миграция `V9__preserve_run_history_on_scenario_deletion.sql` — снимает `CASCADE`, добавляет
      `step_name`/`step_type`, задним числом бэкфиллит `scenario_name`/`step_name`/`step_type` для
      уже существующих строк, пока связанные `test_scenario`/`scenario_step` ещё живы
- [x] Тесты: `startRunSavesScenarioNameDenormalizedOnTheRun`, `getRunReturnsDenormalizedScenarioName`,
      `getRunUsesDenormalizedStepNameAndTypeWhenScenarioStepNoLongerExists` (`ExecutionServiceTest`),
      `preCreatedStepRunsCarryDenormalizedStepNameAndType` (`ScenarioExecutionEngineTest`)
- [x] 146 тестов (было 142), `mvn verify` (JaCoCo) — зелёный

## Sprint 19 — Поиск транзакций по фильтру, имена вместо id в статусах — DONE
Пользователь: (1) при проверке очереди по `naturalKeys` тянется вся очередь целиком (тысячи
транзакций) вместо поиска по фильтру, и из-за этого проверка иногда не находит существующую
транзакцию — заподозрил, что оркестратор отдаёт только начало списка; (2) то же самое нужно и для
ручного аудита при указанных id; (3) статусы запущенного сценария показывают голые numeric id
оркестратора (`"Задание id=1447 ..."`) вместо человекочитаемых имён — непонятно, о чём речь.
Расследование п.1 подтвердило гипотезу пользователя: постраничный перебор `QueueCheckStepExecutor`
был ограничен `MAX_PAGES=50` (×200 = 10 000 элементов) — транзакция за этой границей была
недостижима вообще, не только "неэффективно искалась". В `orc_swagger.json` (не в `OrcService.java`/
`orc_worker.py`) нашёлся нужный, ранее не использованный фильтр эндпоинта.
- [x] `ExchangeQueuesPort`/`ExchangeQueuesClient.listItems` — новая перегрузка с параметрами
      `naturalKey`/`naturalKeyPart`, транслируется в query-параметры оркестратора `NaturalKey`/
      `NaturalKeyPart` эндпоинта `GET .../v2/{id}/Items` (есть в схеме, нигде не задокументированы)
- [x] `QueueCheckStepExecutor.fetchByNaturalKeys` — при непустом `naturalKeys` конфига делает
      отдельный отфильтрованный запрос на каждый ключ вместо перебора всей очереди; финальная
      точная проверка (`startsWith`/равенство) остаётся клиентской стороной — семантика
      `NaturalKeyPart` на стороне оркестратора не подтверждена, подстраховка не помешает
- [x] `QueueAuditService.auditQueueItems`/`RunController.queueItems` — тот же фильтр доступен и в
      ручном аудите (`GET .../queue-items?naturalKey=...&naturalKeyPart=...`), опционально
- [x] `JobStepExecutor`/`StatusPoller` — все статусные сообщения (`detail`, таймаут-эксепшены)
      используют человекочитаемое имя задания (сгенерированное `assignmentName`,
      `_<runId>_<stepId>`) вместо `"id=" + assignmentId`; `StatusPoller.pollUntilTerminal` получил
      третий параметр `assignmentLabel`
- [x] `RpaProjectsPort`/`RpaProjectsClient.findById` — резолвит имя проекта для отображения даже
      когда шаг сконфигурирован через `rpaProjectId` (не `rpaProjectName`); best-effort, не роняет
      шаг при неудаче
- [x] Тесты: `listItemsWithNaturalKeyFiltersOnTheOrchestratorSide` (`ExchangeQueuesClientTest`),
      `findByIdLocatesProjectFromList`/`findByIdReturnsEmptyWhenNoMatch` (`RpaProjectsClientTest`),
      `queriesEachNaturalKeySeparatelyWhenMultipleProvided` + обновлённые prefix/exact-match тесты
      (`QueueCheckStepExecutorTest`), `filtersByNaturalKeyOnTheOrchestratorSideWhenProvided`
      (`QueueAuditServiceTest`), `queueItemsPassesNaturalKeyFilterThrough` (`RunControllerTest`),
      `resolvesProjectLabelByIdForDisplayWhenOnlyIdConfigured`,
      `succeedsEvenWhenProjectLabelLookupByIdFindsNothing`,
      `passesSanitizedAssignmentNameAsLabelToStatusPoller` (`JobStepExecutorTest`),
      `reportsAssignmentByLabelNotRawIdOnCompletion` (`StatusPollerTest`)
- [x] 156 тестов (было 146), `mvn verify` (JaCoCo) — зелёный

## Sprint 20 — Блок запуска при нехватке свободных роботов — DONE
Пользователь: если на оркестраторе свободно меньше двух роботов, запуск сценария нужно блокировать
заранее, а не давать `JOB`-шагу зависнуть в очереди на полчаса до собственного таймаута.
- [x] `RobotDto`/`RobotRunStatus` — новые DTO, `status` mirrors `LTools.Enums.RunStatus`
      (`Unavailable`/`Idle`/`Running`); `RobotDto.isFree()` — `status == IDLE`
- [x] `RobotsPort`/`RobotsClient.list()` — `GET /api/Robots/v2` (тот же вызов, что и в эталонном
      `OrcService.getRpaRobots`), без пагинации (реальные стенды не настолько велики)
- [x] `OrchestratorProperties.minFreeRobots` (по умолчанию 2, `orchestrator.min-free-robots` в
      `application.yml`) — порог настраиваемый, не захардкожен
- [x] `ExecutionService.requireEnoughFreeRobots` — вызывается в начале `startRun`, до создания
      `ScenarioRun`; при нехватке — `ConflictException` (`409`, текст "свободно N из M, требуется
      минимум K"), прогон вообще не создаётся (не тратим место в истории на заведомо
      незапустившийся прогон)
- [x] Простая проверка, не анализирует DAG сценария — блокирует запуск любого прогона (в том числе
      с `startStepId`, даже если точка возобновления не содержит `JOB`-шагов вовсе); осознанное
      упрощение, см. "Открытые риски"
- [x] Тесты: `listReturnsRobotsWithStatusFromV2Endpoint`/`listReturnsEmptyWhenResultIsNull`/
      `wrapsServerErrorIntoOrchestratorApiException` (`RobotsClientTest`),
      `startRunThrowsConflictWhenFewerThanMinFreeRobots`,
      `startRunSucceedsWhenExactlyMinFreeRobotsAvailable`,
      `startRunAllowsCustomMinFreeRobotsThreshold` (`ExecutionServiceTest`)
- [x] 162 теста (было 156), `mvn verify` (JaCoCo) — зелёный

## Sprint 21 — Поллинг доступности роботов для фронта — DONE
Пользователь: хочет постоянный опрос, чтобы на фронте ещё до нажатия кнопки запуска показывать,
разрешён ли он сейчас (и блокировать саму кнопку), а не только узнавать об отказе по факту `409`
на `POST .../run`.
- [x] `RobotAvailabilityResponse(freeRobots, totalRobots, minFreeRobots, launchAllowed)` — новый
      DTO, `launchAllowed = freeRobots >= minFreeRobots`
- [x] `ExecutionService.getRobotAvailability()` — читает тот же снимок, что и
      `requireEnoughFreeRobots()` (Sprint 20); `requireEnoughFreeRobots()` теперь переиспользует
      его вместо дублирования условия, чтобы поллинг и реальная проверка на запуске не могли
      разойтись
- [x] `GET /api/v1/orchestrator/robots-availability` (`OrchestratorController`, новый) —
      read-only, без побочных эффектов, можно опрашивать с любой частотой
- [x] Тесты: `getRobotAvailabilityReportsAllowedWhenEnoughFreeRobots`,
      `getRobotAvailabilityReportsNotAllowedWhenNotEnoughFreeRobots` (`ExecutionServiceTest`),
      `robotsAvailabilityReturnsSnapshotFromService` (`OrchestratorControllerTest`, новый)
- [x] 165 тестов (было 162), `mvn verify` (JaCoCo) — зелёный

## Sprint 22 — expectedStatusCounts: минимум, а не точное совпадение — DONE
Пользователь: при проверке очереди статусы должны сравниваться не строгим равенством 1:1, а
"больше или равно" указанному.
- [x] `QueueCheckStepExecutor.satisfies` — `actual < expected` вместо `actual != expected`:
      статус проходит проверку, когда фактическое количество не меньше ожидаемого
- [x] `describeExpectation` показывает ожидания как `SUCCESS>=N` вместо `SUCCESS=N` — текст
      сообщений (`detail`, таймаут-ошибка) сразу отражает новую семантику, а не вводит в
      заблуждение
- [x] Javadoc `QueueCheckStepConfig.expectedStatusCounts` обновлён под новую семантику
- [x] Тест `succeedsWhenActualCountExceedsExpected` — фактическое количество больше ожидаемого
      минимума проходит проверку (раньше падало бы)
- [x] 166 тестов (было 165), `mvn verify` (JaCoCo) — зелёный

## Sprint 23 — Ручной аудит очереди по умолчанию сужает до своих ключей — DONE
Пользователь: во время запуска сценария просмотр транзакций очереди всё ещё показывает полный
список, а не только по naturalKey созданных (`QUEUE`) или отслеживаемых (`QUEUE_CHECK`) транзакций.
- [x] `QueueItemFinder` (новый, `execution/engine`) — вынесен из `QueueCheckStepExecutor`
      (постраничный полный обзор при пустом `naturalKeys`, фильтр оркестратора по каждому ключу при
      непустом + собственная точная дозачистка) как канонический хелпер поиска элементов очереди —
      не дублировать логику по AGENTS.md
- [x] `QueueAuditService.auditQueueItems` без явного `naturalKey` теперь сам выводит фильтр из
      `ScenarioStep.config` шага: `QUEUE` → naturalKey его собственных `transactions`, `QUEUE_CHECK`
      → его `naturalKeys`/`naturalKeyPrefixMatch`. Явный `naturalKey` в запросе по-прежнему
      перекрывает автоматику. Если шаг ничего не отслеживает по ключу (`minTotalCount`-only
      `QUEUE_CHECK`) или `scenario_step` уже удалён — остаётся честный постраничный обзор всей
      очереди, как раньше (это не баг для этого случая)
- [x] `QueueCheckStepExecutor` переведён на `QueueItemFinder`, свои приватные
      `fetchMatchingItems`/`fetchByNaturalKeys`/`fetchAllPages` удалены
- [x] Тесты: `QueueItemFinderTest` (новый), `autoFiltersToQueueStepsOwnTransactionNaturalKeysByDefault`,
      `autoFiltersToQueueCheckStepsTrackedNaturalKeysByDefault`,
      `showsWholeQueueForQueueCheckStepThatWatchesEntireQueue`,
      `explicitNaturalKeyOverridesAutoDerivedFilter` (`QueueAuditServiceTest`)
- [x] 173 теста (было 166), `mvn verify` (JaCoCo) — зелёный

## Sprint 24 — Fan-in (несколько родителей у одного шага) — DONE
Пользователь: если несколько блоков входят в один — выполнять его только если все предшествующие
блоки, которые в него входят, были выполнены и выполнены успешно. Если сценарий запускается с места
и это одна из веток, входящих в такой узел — выполнять его, потому что другие ветки не существуют
в этом запуске.
- [x] `ScenarioExecutionEngine` переведён с push-рекурсии (`executeStepAsync`: родитель после
      успеха сам вызывает детей) на пулл-модель (`getOrCreateFuture`): узел сам собирает будущие
      своих родителей и ждёт ВСЕХ их, `futuresByStepId` мемоизирует по `stepId` — узел с несколькими
      входящими рёбрами получает одно будущее и выполняется один раз, не дважды параллельно, как
      было раньше (см. старую формулировку в `agents.md`/`architecture.md` — исправлена)
- [x] Успех требуется от всех родителей (`allMatch(SUCCEEDED)`), не "хотя бы одного" — если один упал
      или не выполнялся, fan-in-узел не запускается и остаётся `PENDING`, каскадируется дальше по DAG
- [x] `computeReachable` (BFS от корней по `outgoing`) — родитель, недостижимый в этом прогоне
      (ветка "до" `startStepId`), не входит в `incoming`-фильтр и не блокирует fan-in-узел ожиданием
- [x] Сохранено свойство "не блокировать поток на `.join()`" (Sprint 14) — `.join()` внутри
      `thenComposeAsync` вызывается только после `allOf(parentFutures)`, то есть на уже завершённых
      будущих
- [x] Тесты (`ScenarioExecutionEngineTest`): `fanInStepRunsOnceOnlyAfterAllParentsSucceed`,
      `fanInStepStaysPendingWhenOneParentFails`,
      `fanInStepRunsWhenOtherBranchDoesNotExistInThisRunDueToStartStepId`
- [x] 176 тестов (было 173), `mvn verify` (JaCoCo) — зелёный

## Sprint 25 — Архитектурный ревью: OrchestratorLookup/OrchestratorNarration, ADR-процесс — DONE
`/mattpocock-skills:improve-codebase-architecture` нашёл дублирование того же вида, ради которого
в Sprint 23 выделен `QueueItemFinder` — только для id-резолвинга и текста статусов вместо чтения
очереди. Выбрана кандидатура A из отчёта ревью.
- [x] `OrchestratorNarration` (новый, `orchestrator/util`) — чистые статические функции
      "сырые данные оркестратора → фраза для человека" (`describeRunning`, `describeQueued`,
      `describeQueueError`, `describeExpectation`, `describeActual`, `describeCheckResult`),
      без I/O, в стиле `OrchestratorNames`
- [x] `OrchestratorLookup` (новый, `execution/engine`) — единственный владелец
      `RpaProjectsPort`/`RpaProjectQueuePort` для целей отображения: `resolveProjectId`,
      `resolveProjectLabel`, `findQueueEntries`. `StatusPoller.describeState` и
      `JobStepExecutor`(бывший `describeError`) раньше независимо звали
      `RpaProjectQueuePort.findByAssignment` для одного и того же `assignmentId` — теперь один вызов
- [x] `JobStepExecutor` лишился двух зависимостей (`RpaProjectsPort`, `RpaProjectQueuePort` →
      один `OrchestratorLookup`); свои приватные `resolveProjectLabel`/`resolveProjectId`/
      `describeError` удалены
- [x] `StatusPoller`, `QueueCheckStepExecutor` переведены на `OrchestratorNarration`, свои приватные
      `describeExpectation`/`describeActual`/`describe` в `QueueCheckStepExecutor` удалены
- [x] Тесты: `OrchestratorNarrationTest`, `OrchestratorLookupTest` (новые); `JobStepExecutorTest`/
      `StatusPollerTest` — конструктор оборачивает моки портов в настоящий `OrchestratorLookup`,
      как принято в проекте (`ExchangeQueueProvisioner`, `QueueItemFinder`)
- [x] Введён ADR-процесс (`agents.md`, раздел "ADR"): `docs/adr/0001-...md` (ретроактивно —
      конвенция канонического хелпера, Sprint 23), `docs/adr/0002-...md` (это решение)
- [x] 192 теста (было 176), `mvn verify` (JaCoCo) — зелёный

## Sprint 26 — Аутентификация собственного API: JWT + роли + admin API — DONE
Раньше свой API (11 эндпоинтов) был полностью открыт — авторизация была только к оркестратору.
Погриллено (`/mattpocock-skills:grilling`) и записано в ADR 0003.
- [x] `com.rpatest.auth` (новый пакет: `domain`/`repository`/`service`/`web`/`config`) — `AppUser`
      (роль `ADMIN`/`OPERATOR`/`VIEWER`, `enabled`), `RefreshToken` (хранится только SHA-256 хэш);
      миграции `V10__app_user.sql`, `V11__refresh_token.sql`
- [x] `JwtService` — выпуск/проверка access-токена (HS256, 15 мин, `auth.jwt.secret`, Jasypt
      `ENC(...)`); `RefreshTokenService` — БД-хранимый refresh (7 дней) с ротацией на каждое
      обновление; `AuthService` компонует оба для login/refresh/logout
- [x] `POST /api/v1/auth/{login,refresh,logout}` (`AuthController`, публичные)
- [x] `AdminUserController` (`/api/v1/admin/users`, `ADMIN`-only) — create/list/get/смена роли/
      enabled/сброс пароля через `AppUserService`; деактивация и сброс пароля отзывают все refresh-
      токены пользователя (`RefreshTokenService.revokeAllForUser`)
- [x] `AdminBootstrapRunner` — первый `ADMIN` при старте, если `app_user` пуста
      (`auth.bootstrap-admin.*`), не через Flyway
- [x] `SecurityConfig` — вся матрица доступа в одном месте (`authorizeHttpRequests`, не
      `@PreAuthorize` по контроллерам); все 11 существующих эндпоинтов защищены сразу, не поэтапно
      (кроме `/actuator/health`/`/actuator/info`)
- [x] `ScenarioRun.triggeredBy` теперь из `Authentication`, не из тела запроса (`RunRequest`
      лишился поля `triggeredBy`)
- [x] Зависимости: `spring-boot-starter-security`, `io.jsonwebtoken:jjwt-*` 0.12.6
- [x] Тесты: `JwtServiceTest`, `RefreshTokenServiceTest`, `AuthServiceTest`, `AppUserServiceTest`,
      `AdminBootstrapRunnerTest`, `JwtAuthenticationFilterTest`, `AuthControllerTest`,
      `AdminUserControllerTest`, `AppUserRepositoryIT`/`RefreshTokenRepositoryIT` (Testcontainers),
      `SecurityConfigAuthorizationTest` (единственное место, проверяющее саму матрицу доступа —
      реальный `SecurityConfig`, `@WithMockUser` с разными ролями). Существующие `*ControllerTest`
      получили `@AutoConfigureMockMvc(addFilters = false)` + `@MockBean JwtService`
      (`JwtAuthenticationFilter` как `Filter`-бин попадает в `@WebMvcTest`-слайс независимо от
      `addFilters`)
- [x] Введён ADR 0003 (`docs/adr/0003-own-api-authentication-jwt-rbac.md`)
- [x] 251 тест (было 192; `AppUserRepositoryIT`/`RefreshTokenRepositoryIT` не запускались в этой
      среде — нет Docker, как и ранее `ScenarioStepRepositoryIT`), `mvn verify` (JaCoCo) — зелёный

## Sprint 27 — Токены в secure-куках + HTTPS, досрочный выход QUEUE_CHECK — DONE
Пользователь: токены в JSON-теле — читаемы/подменяемы через localStorage/JS; запихнуть в secure
куки и включить HTTPS (или дать инструкцию). Плюс: `QUEUE_CHECK` зря досиживает полный timeout,
когда все отслеживаемые по фильтру транзакции уже в конечном статусе и больше не изменятся.
- [x] `AuthCookies` (новый, `auth/web`) — единственный владелец построения/чтения/очистки
      `access_token` (`Path=/`) и `refresh_token` (`Path=/api/v1/auth`), оба `HttpOnly`+`Secure`+
      `SameSite=Strict`. `TokenResponse` лишился `accessToken`/`refreshToken` (только
      `expiresInSeconds`); `RefreshRequest` удалён — `/refresh`/`/logout` читают токен из куки
- [x] `JwtAuthenticationFilter` — сначала кука, fallback `Authorization: Bearer` (curl/тесты)
- [x] `auth.cookies.secure` (`AUTH_COOKIES_SECURE`, дефолт `true`) — `false` только для локальной
      разработки без TLS
- [x] `server.forward-headers-strategy: framework` в `application.yml` + закомментированный шаблон
      `server.ssl.*` — для варианта "TLS прямо в приложении"
- [x] `HTTPS_SETUP.md` (новый) — инструкция: TLS на реверс-прокси (рекомендуется) или напрямую в
      Spring Boot, локальная разработка без TLS, предупреждение про `SameSite=Strict` и
      кросс-origin фронт
- [x] CSRF сознательно не включён — обоснование в ADR 0004 (опора на `SameSite=Strict`)
- [x] `QueueCheckStepExecutor.pollUntilSatisfied` — досрочный `StepExecutionException`, если при
      непустом `naturalKeys` все найденные транзакции в конечном статусе (`isAllTerminal`) и это
      повторилось два опроса подряд (один подтверждающий, на случай появления новой транзакции с
      тем же ключом/префиксом между опросами) — не ждёт оставшийся `timeout` впустую. Без
      `naturalKeys` (проверка всей очереди) правило не действует — набор не закрыт
- [x] Введён ADR 0004 (`docs/adr/0004-cookie-based-tokens-and-https.md`)
- [x] Тесты: `AuthCookiesTest` (новый); `JwtAuthenticationFilterTest`/`AuthControllerTest`
      переписаны под куки; `QueueCheckStepExecutorTest` — 3 новых (досрочный выход, НЕ досрочный
      выход без `naturalKeys`, сброс "стабильности" при появлении новой транзакции между опросами)
- [x] `FRONTEND_INTEGRATION.md` обновлён под куки (см. ниже в этом же спринте — вместо хранения
      токенов фронт просто ничего не делает, браузер прикладывает куку сам)
- [x] 265 тестов (было 251), `mvn verify` (JaCoCo) — зелёный

## Sprint 28 — GET /api/v1/auth/me (роль для фронта без декодирования JWT) — DONE
Прямое следствие Sprint 27: токен в `HttpOnly`-куке, фронт спросил, как теперь узнать роль.
- [x] `AuthController.me()` — `GET /api/v1/auth/me` → `{"username", "role"}`, роль берётся из
      `GrantedAuthority` текущего `Authentication`, без похода в БД
- [x] Доступен любой аутентифицированной роли — не размечен под конкретную роль в
      `SecurityConfig`, просто попадает под общее `anyRequest().authenticated()`
- [x] По пути выявлена и задокументирована ловушка: `Authentication` как параметр метода
      контроллера резолвится через `HttpServletRequest.getUserPrincipal()`, который заполняет сам
      фильтр-чейн — в `@WebMvcTest` с `addFilters=false` параметр приходит `null` даже под
      `@WithMockUser` (тот пишет прямо в `SecurityContextHolder`, не трогает фильтры). Исправлено
      на `SecurityContextHolder.getContext().getAuthentication()` внутри метода — как уже делал
      `RunController`; задокументировано в `agents.md`, чтобы не наступить снова
- [x] Тесты: `AuthControllerTest` (2, мэппинг username/role), `SecurityConfigAuthorizationTest` (2 —
      401 без аутентификации, 200 для VIEWER — самой слабой роли, подтверждает "не role-gated")
- [x] `FRONTEND_INTEGRATION.md` обновлён — новый раздел с примером запроса/ответа
- [x] 269 тестов (было 265), `mvn verify` (JaCoCo) — зелёный

## Sprint 29 — Без скрытых таймаутов, смена своего пароля, редактируемая матрица прав — DONE
Пользователь: убрать скрытые таймауты на компонентах (всё опционально и в сценарии — и в задании,
и в очередях); нормальный эндпоинт смены пароля самим пользователем; управление матрицей
роль-права админом. Решения — ADR 0005, ADR 0006.
- [x] Таймауты (ADR 0006): из `OrchestratorProperties.Polling` и `application.yml` убраны
      `polling.timeout: 30m` и `queue-check-polling.timeout: 10m` — остался только `interval`.
      `JobStepConfig` получил `timeoutSeconds` и `pollIntervalSeconds` (раньше у JOB не было вообще),
      `StatusPoller.pollUntilTerminal(..., timeout, interval)`, `QueueCheckStepExecutor` берёт
      таймаут только из `config.timeoutSeconds`; `null` = без лимита. HTTP connect/read-таймауты
      (один сетевой вызов) оставлены — видны в `application.yml`
- [x] `POST /api/v1/auth/change-password` — `AuthService.changePassword`: текущий пароль
      обязателен (`400`), новый не должен совпадать с текущим, отзываются все refresh-токены,
      текущей сессии новая пара кук. Любая аутентифицированная роль
- [x] Матрица прав (ADR 0005): `Permission` (10 прав), `RolePermission` + миграция `V12` (засеяна
      прежней жёсткой матрицей), `RolePermissionService` (кэш, сброс после коммита, `ADMIN` = всё и
      не редактируется), `PermissionAuthorization` (`AuthorizationManager`),
      `SecurityConfig` переведён с `hasRole` на `access(permissions.has(...))` + `denyAll()` на всё
      не сопоставленное праву
- [x] Admin API матрицы: `GET /api/v1/admin/permissions`, `GET /api/v1/admin/roles`,
      `PUT /api/v1/admin/roles/{role}/permissions` (право `ROLE_MANAGE`)
- [x] `GET /api/v1/auth/me` теперь возвращает `permissions` (UI-гейтинг по правам, не по роли)
- [x] Тесты: `RolePermissionServiceTest`, `RolePermissionControllerTest`, `RolePermissionRepositoryIT`
      (Docker нужен, здесь не запускается), `AuthServiceTest`/`AuthControllerTest` (смена пароля,
      `/me` с правами), `SecurityConfigAuthorizationTest` переписан (все права, динамическая смена
      матрицы, `denyAll` для не сопоставленного, `/me`/смена пароля для любой роли),
      `StatusPollerTest`/`QueueCheckStepExecutorTest`/`JobStepExecutorTest` (без таймаута ждёт
      дольше прежнего дефолта, таймаут/интервал из конфига шага)
- [x] 302 теста (было 269), `mvn verify` (JaCoCo) — зелёный

## Sprint 30 — Английские логи, логирование каждого запроса — DONE
Windows-консоль ломала кириллицу в выводе, поэтому всё, что попадает в терминал, переведено на
английский.
- [x] Переведено на английский: все `log.*`-сообщения, `StepRun.detail` (дублируется в лог через
      `StepProgressReporter`), `OrchestratorNarration`, сообщения `StepExecutionException`/
      `OrchestratorApiException`/`OrchestratorAuthException` (попадают в стектрейсы). Сообщения,
      которые видит только вызывающий API (`NotFoundException`, `ConflictException`, валидация,
      401/403, описания прав) — оставлены на русском, в терминал они не попадают. Комментарии в
      коде тоже не трогались.
- [x] `RequestLoggingFilter` (`common/web`): на каждый запрос две строки — `--> METHOD URI from
      user=... ip=...` и `<-- METHOD URI status=... user=... ip=... took N ms`. Не `@Component`:
      добавлен в security-цепочку сразу после `JwtAuthenticationFilter`, чтобы пользователь был уже
      известен, а 401/403 тоже попадали в лог. Заголовки, куки и тела не логируются.
- [x] `ExecutionServiceTest`: два теста держали устаревшее допущение (`UNAVAILABLE` — не свободен),
      после коммита `587cd05` (`RobotDto.isFree` намеренно считает `UNAVAILABLE` свободным — не
      менять) падали — третий робот в них заменён на `RUNNING`
- [x] 306 тестов (+4 `RequestLoggingFilterTest`), `mvn verify` — зелёный

## Sprint 31 — Список прогонов, `triggeredBy` в ответе — DONE
- [x] `GET /api/v1/runs?scenarioId&page&size` (право `RUN_READ`): `PageResponse` (`common/web`),
      строка — `RunSummaryResponse` (без `steps`, чтобы список не грузил шаги каждого прогона).
      Сортировка `startedAt` DESC NULLS FIRST (ещё не начавшийся `PENDING` — самый новый), потом
      `id` DESC. `size` 1..100 (по умолчанию 20), `page >= 0`, иначе `400`. Неизвестный
      `scenarioId` — пустая страница, не `404`.
- [x] `RunResponse` получил `triggeredBy` (`scenarioName` уже был). Раньше `triggeredBy` хранился в
      БД, но в ответ API не попадал.
- [x] `SecurityConfig`: `GET /api/v1/runs` явно в матчере `RUN_READ` рядом с `/api/v1/runs/**`
- [x] Тесты: `ExecutionServiceTest` (сортировка, фильтр, границы, `triggeredBy`),
      `RunControllerTest` (страница, параметры, 400), `SecurityConfigAuthorizationTest` (право,
      401). Репозиторный тест на реальной БД не добавлен (Docker недоступен) — сортировку с
      `NULLS FIRST` на Postgres стоит проверить на стенде.

## Sprint 32 — Отчёт по прогону (HTML, Sankey, письмо) — DONE
По запросу: читает человек, формат HTML (в PDF - через печать браузера), уведомление через очередь
`SND`, язык русский, плюс диаграмма Sankey. Решения и отвергнутые варианты - ADR 0007.
- [x] V13: `step_run.result` (JSONB) и таблица `run_report` (снимок отчёта)
- [x] Исполнители пишут структурированный итог шага: `JOB` (робот, задание, проект, времена),
      `QUEUE` (очередь, создана ли, сколько транзакций добавлено), `QUEUE_CHECK` (ожидалось,
      получено, пройдена ли, до 200 транзакций; пишется на каждом опросе, до возможного throw)
- [x] `RunCompletionHandler` после `engine.runScenario` (в `finally`): пересобирает снимок, шлёт
      письмо. Никогда не бросает. Движок про отчёты ничего не знает
- [x] `GET /api/v1/runs/{id}/report` (право `RUN_READ`): HTML по умолчанию, `?format=json` - снимок;
      `409`, пока прогон идёт; прогоны до этой версии получают снимок при первом запросе. Ответ с
      `Content-Security-Policy: default-src 'none'`, всё из пользовательских данных экранируется
- [x] Sankey - серверный inline SVG (`SankeyDiagram`): столбцы по глубине DAG, ширина потока и высота
      блока по времени, цвет по статусу
- [x] Письмо: `report.notification.*` (по умолчанию ВЫКЛЮЧЕНО), транзакция в очередь `SND`:
      `value=Sandbox`, `naturalKey=ID<ддММггггЧЧммсс>_SandboxReport`, `Mail_To` = логин запустившего + `@` + `report.notification.mail-domain`
      (`REPORT_MAIL_DOMAIN`; логин с `@` не меняется, пустой домен - логин как есть),
      `Mail_Subject`, `Mail_Body` (короткая справка в минимальном HTML + ссылка, если задан
      `public-base-url`). Сбой отправки логируется и не влияет на прогон
- [x] 351 тест (+45), `mvn verify` зелёный. Не проверено на живом оркестраторе: формат `Mail_Body`
      (HTML или текст ждёт ваш почтовик), существование очереди `SND`, `NULLS FIRST`/JSONB на
      Postgres (Docker недоступен, `*RepositoryIT` не запускались)

## Sprint 33 — Письмо по флагу запуска, человеческие комментарии в отчёте — DONE
- [x] `POST /api/v1/scenarios/{id}/run`: поле `sendReportByMail` (необязательное, по умолчанию не
      отправлять). Письмо уходит тому, кто запустил, по завершении прогона. Если отправка не
      включена на сервере (`report.notification.enabled`) - `400` ДО создания прогона (не обещаем
      письмо молча). `enabled` теперь значит "сервер умеет слать письма", а не "слать на каждый прогон"
- [x] `GET /api/v1/report/settings` -> `{mailAvailable}` (любой аутентифицированный): показывать ли
      галочку "прислать отчёт на почту"
- [x] Колонка "Комментарий", блок "Причина ошибки" и письмо - человеческий русский текст из
      структурированного `result` (`StepComment`): "Задание выполнено на роботе «r» за 5 мин",
      "Проверка не пройдена: «Успешно»: ожидалось не менее 5, получено 2. Истекло время ожидания..."
      Сырой текст ошибки (английский, технический) - в сворачиваемом блоке "Технические детали".
      Для этого `result` получил `robotError` (JOB) и `failureReason` ALL_FINAL/TIMEOUT (QUEUE_CHECK)
- [x] Экранирование HTML - `htmlEscape(text, "UTF-8")`: только служебные символы, кириллица и «» остаются как есть
- [x] Sankey убран (ADR 0008): не читался и не масштабировался на сценарии в 40 шагов подряд и 7
      параллельных веток. В отчёте две диаграммы (серверный SVG): "Ход выполнения" и "Хронология"
      (`GanttDiagram` - строка на шаг по общей шкале времени, плотнее при > 20 шагов)
- [x] "Ход выполнения" (`StepGraphDiagram`, ADR 0009) - граф сверху вниз в стиле git-истории, без
      текста внутри: блок с порядковым номером = ссылка на строку таблицы "Шаги" (`#step-N`, строка
      подсвечивается). Форма по типу (круг - очередь, квадрат - проверка, треугольник вниз - задание),
      цвет - статус, размер - длительность. Параллельные шаги делят строку: 7 веток -
      7 столбцов, 40 подряд - узкая колонка. Описание блоков - легенда и абзац над диаграммой.
      Проверено глазами на синтетике 38 шагов/7 веток и 40 подряд, ссылки проверены в браузере
- [x] Граф шагов, доработка: ветки растянуты на всю ширину блока (шаг колонки = 1000 / число веток,
      но не больше 320 и не меньше, чем влезает самый большой блок), главная ветка - в центре, остальные
      чередуются по сторонам, поэтому вилка из корня и слияние симметричны. Размер блока - линейно
      между самым быстрым и самым долгим выполненным шагом (радиус 10 -> 30), а не логарифм: самый
      долгий шаг сразу виден. Одинаковые длительности - средний размер. Между строками больше места,
      когда есть параллельные ветки (изгибам нужна высота)
- [x] Размер блока: логарифм между самым быстрым и самым долгим вместо линейной шкалы - при одном
      долгом шаге линейная рисовала все остальные одинаково, хотя времена различаются в разы.
      Крайности те же (10 и 30), середина расходится геометрически. Размер относителен внутри прогона
- [x] 391 тест

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
- **`V9__preserve_run_history_on_scenario_deletion.sql` не прогонялась на реальной БД** (нет
  Docker в этой сессии, см. выше) — `DROP CONSTRAINT scenario_run_scenario_id_fkey`/`..._step_id_fkey`
  предполагает автосгенерированное имя ограничения Postgres (`<table>_<column>_fkey`, как оно
  называется при неименованном inline `REFERENCES` в `CREATE TABLE`, см. `V2__scenario.sql`/
  `V3__execution.sql`) — стоит явно проверить перед мёржем на реальной БД (`\d scenario_run` /
  `\d step_run` в psql), что имена ограничений действительно такие, иначе миграция упадёт с
  "constraint does not exist".
- **`retray`/`maxRetray` не подтверждены на реальном стенде.** Поля добавлены из схем
  `orc_swagger.json` (`ExchangeQueueValueDto.retray`, `ExchangeQueueDto.maxRetray`) по описанию
  ("количество повторных помещений элемента в очередь при фиксации статуса ошибка"), но, в отличие
  от других найденных на этом стенде расхождений, ни разу не проверялись вживую — стоит прогнать
  сценарий с реальной ошибкой в задании и настроенным `maxRetray > 0` и убедиться, что
  `QUEUE_CHECK` действительно ждёт исчерпания повторов, а не считает `ERROR` финальным сразу же
  (или наоборот — что `IN_PROGRESS` из-за этой логики не зависает вечно, если оркестратор на
  самом деле не перекладывает транзакцию автоматически).
- **`NaturalKey`/`NaturalKeyPart` (Sprint 19) не подтверждены на реальном стенде.** Параметры
  фильтрации `GET /api/ExchangeQueues/v2/{id}/Items` найдены только в схеме `orc_swagger.json` —
  ни `OrcService.java`, ни `orc_worker.py` их не используют, значит рабочие эталонные клиенты
  этот путь не проверяли. Особенно важно перепроверить `NaturalKeyPart`: неизвестно, соответствует
  ли она нашему контракту "строго префикс" или это скорее "вхождение в любом месте строки" —
  на этот случай уже есть подстраховка (финальная точная фильтрация на своей стороне, см.
  `architecture.md`), но стоит вживую убедиться, что сам параметр `NaturalKey`/`NaturalKeyPart`
  не игнорируется молча (как было с `GET /api/ExchangeQueues/{id}/Items` без версии, Sprint 11) —
  простейшая проверка: `QUEUE_CHECK` с `naturalKeys` на ключ, которого в большой очереди тысячи
  элементов заведомо не имеют, должен быстро дойти до таймаута с "фактически=0", а не найти
  случайные посторонние транзакции.
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
- **`GET /api/Robots/v2` (Sprint 20) без пагинации не подтверждён на реальном стенде с большим
  числом роботов.** Эталонный `OrcService.getRpaRobots` тоже вызывает без `pageNumber`/`pageSize`,
  но неизвестен реальный размер страницы по умолчанию у оркестратора — если роботов на стенде
  больше него, `RobotsClient.list()` увидит не всех, и подсчёт свободных окажется заниженным
  (ложные `409` при фактически достаточном числе роботов). Стоит явно проверить на стенде с
  десятками+ роботов, либо подстраховаться постраничным перебором по аналогии с
  `QueueCheckStepExecutor.fetchAllPages`.
- **Блок по роботам (Sprint 20) не анализирует топологию сценария.** Блокирует `POST .../run`
  целиком, если свободных роботов меньше `min-free-robots`, даже если в конкретном сценарии (или
  точке возобновления через `startStepId`) вообще нет `JOB`-шагов, которым нужен робот —
  например, сценарий из одних `QUEUE`/`QUEUE_CHECK` шагов получит `409` без всякой причины. Если
  это окажется проблемой на практике — доработка: считать `JOB`-шаги, реально достижимые от корней/
  `startStepId` по DAG, и пропускать проверку, если их ноль.

## Backlog (за рамками текущего скоупа)
- Аутентификация/авторизация собственного REST API (осознанно не делали на этом этапе).
- Строгая проверка несовпавших ключей в `JobStepConfig.arguments` (сейчас молча игнорируются).
- UI/дашборд поверх REST API для визуального конструирования сценариев и просмотра прогонов.
- Поддержка fan-in в `ScenarioExecutionEngine` (см. выше).
