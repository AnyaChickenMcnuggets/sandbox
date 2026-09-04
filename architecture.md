# Архитектура

## Терминология

Оркестратор — **Primo RPA** (внутреннее имя API — `LTools WebApi`, см. [orc_swagger.json](orc_swagger.json)).
Прямых аналогов терминов UiPath нет, соответствие:

| Термин в требованиях | Сущность Primo RPA Orchestrator API | Наш домен |
|---|---|---|
| Проект | `RpaProjects` (список — `GET /api/RpaProjects/v3/short`) | `RpaProjectsPort.findByName` → `JobStepConfig.rpaProjectName` (или напрямую `rpaProjectId`) |
| Job / Задание | `Assignments` | `ScenarioStep(type=JOB)` → `StepRun.orchestratorAssignmentId` |
| Аргументы задания | `RpaProjectVariables` (`/Assignment/{id}`) | `JobStepConfig.arguments` |
| Очередь транзакций | `ExchangeQueues` | `ScenarioStep(type=QUEUE)` → `StepRun.orchestratorQueueId` |
| Транзакция очереди | `ExchangeQueueItem` / `ExchangeQueueValueDto` | `QueueStepConfig.transactions` / `QueueItemResult` |
| Статус транзакции очереди | нет отдельного поля — выводится из `readedRobotAt`/`lastEventType` | `QueueItemDerivedStatus` (NEW/IN_PROGRESS/SUCCESS/ERROR/BUSINESS_ERROR) |
| Очередь ожидания запуска проекта | `RpaProjectQueue` | `RpaProjectQueuePort` (диагностика/текст ошибки) |
| Реальный запуск проекта на роботе | `RpaProjectLaunches` | `RpaProjectLaunchesPort` → `StatusPoller` (источник истины о завершении) |
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
3. `JobStepExecutor`: сначала определяет `rpaProjectId` — если в `config` задано
   `rpaProjectName`, ищет его через `RpaProjectsPort.findByName` (`GET /api/RpaProjects/v3/short`,
   по точному совпадению имени; при нескольких версиях с одинаковым именем предпочитает
   `active=true`); если имя не найдено — шаг сразу падает с понятным сообщением, не доходя до
   создания Assignment. Если `rpaProjectName` не задано — используется `rpaProjectId` напрямую.
   Затем создаёт `Assignment` (`POST /api/Assignments/v2` — эндпоинт v1 без версии в
   swagger присутствует, но не актуален на реальном стенде), выставляет аргументы
   (`PUT /api/RpaProjectVariables/Assignment/{id}`), стартует (`PUT /api/Assignments/{id}/Start`),
   передаёт управление `StatusPoller`.
   **`AssignmentStatus.COMPLETE` (`GET /api/Assignments/v2/{id}`) не используется как признак
   успеха вообще** — он отражает только то, что оркестратор принял проект в очередь на выполнение
   (`RpaProjectQueue`), а не то, что какой-то робот его реально забрал и доделал. Вместо этого
   `StatusPoller` с заданным интервалом (`orchestrator.polling.interval`) опрашивает
   `GET /api/RpaProjectLaunches/assignment/{assignmentId}` — это записи о **реальных запусках
   проекта на роботах** (кто из роботов взял, во сколько реально начал `robotStartedAt`, во сколько
   закончил `completedAt`/`killedAt`, `success`). Пока такой записи нет — задание либо ещё в
   `RpaProjectQueue` (ждёт свободного робота), либо уже выполняется, но не завершилось. Терминал —
   появление записи с `completedAt`/`killedAt`; `JobStepExecutor` завершает шаг `FAILED`, если
   `success != true`, дополнительно обогащая сообщение об ошибке текстом из `RpaProjectQueue`
   (`errorMsg` — туда пишется причина сбоя выполнения). Таймаут (`orchestrator.polling.timeout`)
   даёт разное сообщение в зависимости от того, где застряло задание — всё ещё в
   `RpaProjectQueue` (робот не подхватил) или уже выполняется на роботе, но не завершилось, или не
   найдено вообще нигде (см. `StatusPoller.buildTimeoutMessage`).
4. `QueueStepExecutor` / `QueueCheckStepExecutor` создают/проверяют `ExchangeQueue`
   **идемпотентно** через общий `ExchangeQueueProvisioner.ensureExists`: сначала `findByName`,
   и только если очереди с таким именем ещё нет — `POST /api/ExchangeQueues`. Это относится и к
   входной очереди (данные для задания), и к выходной (создаётся заранее, до старта задания, чтобы
   заданию было куда писать) — повторный прогон сценария без `cleanup` не падает на конфликте
   имени, а просто переиспользует существующую очередь. `QueueStepExecutor` после этого добавляет
   транзакции-шаблоны (`PUT /api/ExchangeQueues/v2/enqueue/{queueName}` — рабочий Python-клиент
   `orc_worker.py` для этого использует другой эндпоинт, `PUT /api/ExchangeQueues/{id}/Items/Add`;
   расхождение зафиксировано как риск в `roadmap.md`, пока не подтверждено на реальном стенде).
4a. `QueueCheckStepExecutor` (тип шага `QUEUE_CHECK`) — **не про то, запустилось ли задание**
   (это уже гарантирует `JobStepExecutor`/`StatusPoller`, см. п. 3), а про бизнес-результат:
   поллит очередь (`GET /api/ExchangeQueues/v2/{id}/Items`, постранично — **не** без версии, тот
   эндпоинт неактуален и молча возвращает не тот формат ответа, см. ниже) до тех пор, пока фактические
   количества элементов по производному статусу (`QueueItemDerivedStatus`, опционально
   отфильтрованные по списку `naturalKey`) не совпадут с ожидаемыми из `config.expectedStatusCounts`
   / `config.minTotalCount`, либо не истечёт `orchestrator.queue-check-polling.timeout`. **Удалённые
   транзакции (`ExchangeQueueValueDto.deletedAt != null`) исключаются из выборки до подсчёта** —
   иначе удаление элемента из очереди (вручную или самим оркестратором) искажает и общий счётчик, и
   распределение по статусам, которое сравнивается с ожиданием сценария (`QueueAuditService`,
   ручной аудит из п. 4 `TESTING.md`, удалённые транзакции по-прежнему показывает — там это полезная
   информация для отладки, а не критерий прохождения проверки). Фильтр по
   `naturalKeys` работает как точное совпадение по умолчанию (для входной очереди — мы сами знаем
   точные ключи отправленных транзакций) либо как совпадение по префиксу при
   `config.naturalKeyPrefixMatch=true` (для выходной очереди — базовый ключ сквозной от входа к
   выходу, но на выходе к нему может дописываться суффикс для трассировки при разветвлении одной
   входной транзакции на несколько выходных). Сценарий явно падает (`StepExecutionException` с
   текстом "ожидалось X, фактически Y"), если распределение по статусам не совпало с ожиданиями
   автора сценария — например "0 Error, 2 BusinessError, 3 Success". Очередь для проверки тоже
   идемпотентна (`ExchangeQueueProvisioner`) — если её ещё нет, создаётся пустой, и шаг просто
   ждёт появления элементов до таймаута, а не падает немедленно.
5. По завершении шага движок находит исходящие рёбра и параллельно (`CompletableFuture.allOf`)
   запускает все дочерние шаги — это и есть поддержка "разветвления на две очереди после
   определённого задания". **Важно:** переход к следующим шагам реализован через
   `thenComposeAsync` (не `runAsync(...).join()`) — ни один поток пула `scenarioExecutionExecutor`
   не блокируется в ожидании детей, иначе на цепочке длиннее `corePoolSize` пул гарантированно
   виснет (найден и исправлен на реальном стенде, см. `roadmap.md`, Sprint 14: `Java
   ThreadPoolExecutor` не создаёт потоки сверх `corePoolSize`, пока очередь не заполнена, поэтому
   рекурсивно блокирующиеся друг на друге потоки никогда не получают подкрепления из
   `maxPoolSize`). Не возвращайте эту схему при доработке движка.
6. Статус `ScenarioRun` — агрегат статусов всех `StepRun` (SUCCEEDED, если все SUCCEEDED; FAILED,
   если хоть один FAILED; иначе RUNNING). Внешний клиент узнаёт о прогрессе, поллингом
   `GET /api/v1/runs/{runId}` (наш API), что закрывает требование "контроль выполнения в реальном
   времени" без WebSocket.
7. `POST /api/v1/scenarios/{id}/cleanup` берёт `orchestrator_assignment_id`/`orchestrator_queue_id`
   последнего `ScenarioRun` и вызывает `DELETE` в оркестраторе для каждого.

## Наблюдаемость выполнения (live-прогресс и логи)

Проблема, с которой пользователь столкнулся на реальном стенде: `GET /api/v1/runs/{runId}` до
этого показывал только `stepId`+`status`, а долгий шаг (ждёт робота в очереди проектов, выполняется
на роботе, поллит очередь) всё это время выглядел просто как `RUNNING` без каких-либо деталей — и
по логам сервера тоже было не восстановить, что происходит.

- **`StepRun.detail` / `detail_updated_at`** (`V5__step_run_detail.sql`) — свободный текст с
  текущей фазой шага, обновляемый на каждом значимом переходе (не только при смене статуса).
  Примеры значений на всём протяжении *одного* `JOB`-шага: `"Ищу проект 'X' в оркестраторе"` →
  `"Создаю задание для проекта 'X' (id=42)"` → `"Задание поставлено в очередь оркестратора,
  ожидаю подхвата роботом"` → `"Выполняется на роботе 'robot-1' (запущено в ...)"` →
  `"Завершено успешно на роботе 'robot-1'"`. Для `QUEUE_CHECK` — `"Проверка очереди 'X'
  (попытка #7): всего=12 SUCCESS=10 ERROR=2, ожидается SUCCESS=10 ERROR=2"` на каждой итерации
  поллинга, а не только в момент финального успеха/таймаута.
- **`StepProgressReporter`** (`execution/engine/StepProgressReporter.java`) — единая точка входа
  для публикации прогресса: `report(stepRun, "текст")` одним вызовом (1) сохраняет `StepRun.detail`
  в БД и (2) пишет ту же строку в лог на уровне INFO с `step_run`-идентификатором. Все
  долгоживущие исполнители/поллеры (`JobStepExecutor`, `QueueStepExecutor`, `QueueCheckStepExecutor`,
  `StatusPoller`) вызывают его на каждом значимом переходе состояния — так решается сразу и "не
  видно, что происходит сейчас" (через API), и "мало логов" (через тот же вызов).
- **Полная топология видна с самого начала прогона.** `runScenario` заводит `StepRun(PENDING)` на
  **каждый** шаг сценария сразу, до обхода DAG — а не в момент, когда обход до шага реально
  дошёл. Раньше шаг, ожидающий своей очереди (например, `QUEUE_CHECK` после ещё выполняющегося
  `JOB`), просто отсутствовал в ответе `GET /api/v1/runs/{runId}` — по ответу нельзя было понять,
  есть ли он вообще в сценарии, пока предок не завершится. Теперь такой шаг сразу виден со
  статусом `PENDING` (без `detail`/`startedAt`), а `runStep` при реальном старте шага находит и
  переиспользует эту же строку (`findByScenarioRunIdAndStepId`) вместо создания новой. Шаг,
  который так и не был достигнут (предок упал/сценарий остановлен), остаётся `PENDING` навсегда —
  это тоже осмысленная информация ("сценарий до него не дошёл"), а не ошибка.
- **`steps[]` в ответе всегда отсортирован по `ScenarioStep.position`** (порядок, в котором шаги
  заданы в сценарии — та же величина, по которой `stepRepository.findByScenarioIdOrderByPosition`
  отдаёт шаги движку), а не по порядку возврата `StepRunRepository.findByScenarioRunId` — тот
  ничего не гарантирует про порядок строк (особенно с учётом Sprint 15: все `StepRun` заводятся
  одним `saveAll` разом, а не по одному по ходу выполнения). Сортировка — в
  `ExecutionService.toResponse`.
- **`GET /api/v1/runs/{runId}`** (`StepRunResponse`) теперь возвращает по каждому шагу не только
  `stepId`+`status`, но и `stepName`, `stepType` (`JOB`/`QUEUE`/`QUEUE_CHECK` — какой это шаг, без
  похода в `GET /api/v1/scenarios/{id}` за расшифровкой), `detail`+`detailUpdatedAt` (текущая фаза и
  когда она последний раз менялась). `ExecutionService` строит это, присоединяя `StepRun` к
  `ScenarioStep` через `ScenarioStepRepository.findAllById` по набору `stepId` рана.
- **Уровень логирования.** `logging.level.com.rpatest: INFO` в `application.yml` — по умолчанию
  видно: старт/финиш рана, начало/успех/провал каждого шага, следующие шаги DAG после успеха,
  каждый вызов `StepProgressReporter.report`. `DEBUG` (не включён по умолчанию) добавляет
  тик поллинга (`StatusPoller`/`QueueCheckStepExecutor`) на каждой итерации, а не только когда
  меняется `detail`.

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

Это правило по-прежнему обязательно для `JOB` (сам он очередей не создаёт), но не критично для
`QUEUE_CHECK` — тот идемпотентно создаёт отсутствующую очередь сам (см. п. 4а) и просто ждёт
появления элементов, так что перепутанный порядок с ним не роняет сценарий, а лишь ждёт дольше.

### Паттерн: идемпотентные операции создания сущностей оркестратора

`ExchangeQueueProvisioner.ensureExists` (find-or-create) — общий паттерн для любой будущей
операции создания именованной сущности, которую сценарий может повторно запускать без `cleanup`:
сначала искать по имени, создавать только при отсутствии. `Assignment` под этот паттерн
сознательно не подходит — каждый прогон обязан создавать новый (`_<runId>_<stepId>` в имени) и
не переиспользовать чужой, поскольку задание одноразовое по своей природе (см. агентские заметки
про "один активный запуск на проект").

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
