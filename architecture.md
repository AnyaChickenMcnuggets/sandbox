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
- **История прогонов переживает удаление/редактирование сценария.** `scenario_run.scenario_id` и
  `step_run.step_id` — `ON DELETE SET NULL` (не `CASCADE`, как было изначально), оба столбца
  nullable. Удаление сценария (`DELETE /api/v1/scenarios/{id}`) и редактирование
  (`PUT .../scenarios/{id}` — пересоздаёт `scenario_step` с нуля, старые id пропадают) больше не
  стирают `scenario_run`/`step_run` целиком — они просто теряют связь с уже несуществующим
  `scenario_step`/`test_scenario`. Чтобы после этого было что показать, имя и тип денормализованы
  на момент создания: `scenario_run.scenario_name` (из `TestScenario.name`) и
  `step_run.step_name`/`step_type` (из `ScenarioStep.name`/`type`) — заполняются один раз в момент
  запуска (`ScenarioExecutionEngine`/`ExecutionService.startRun`), а не вычисляются на лету join'ом.
  `RunResponse.scenarioName`/`StepRunResponse.stepName`/`stepType` отдают именно эти
  денормализованные значения — не требуют отдельного `GET /api/v1/scenarios/{id}` на фронте и не
  становятся `null`, если сценарий/шаг с тех пор удалён. `ExecutionService.toResponse` всё ещё
  делает join с `scenario_step` (`findAllById`), но только для сортировки `steps[]` по
  `ScenarioStep.position` — если шаг уже удалён, он просто сортируется последним
  (`Integer.MAX_VALUE`), это не мешает отобразить сам шаг.

## Выполнение сценария

0. **Предусловие запуска: минимум свободных роботов.** Перед созданием `ScenarioRun`
   `ExecutionService.startRun` вызывает `GET /api/Robots/v2` (`RobotsPort`/`RobotsClient`, тот же
   вызов, что и в эталонном `OrcService.getRpaRobots`) и считает свободными роботов со `status == Idle` или
   `Unavailable` (`RobotDto.isFree()`, mirrors `LTools.Enums.RunStatus`: `Unavailable`/`Idle`/
   `Running`; `Unavailable` считается свободным намеренно — решение владельца проекта). Если
   свободных меньше `orchestrator.min-free-robots` (по умолчанию 2) — прогон не создаётся вообще,
   `409 CONFLICT` с текстом вида "свободно 1 из 3, требуется минимум 2". Смысл: без этого `JOB`-шаг
   уходил в `RpaProjectQueue` и висел там в ожидании робота (раньше — до скрытого таймаута в
   30 минут, теперь без лимита, если автор сценария не задал `timeoutSeconds`, ADR 0006) —
   пользователь узнавал о нехватке роботов только тогда, а не сразу при попытке запуска. Проверка простая и **не анализирует DAG
   сценария** — блокирует запуск любого прогона независимо от того, сколько в нём реально `JOB`-шагов
   (в том числе при `startStepId`, даже если точка возобновления не содержит ни одного `JOB`); это
   осознанное упрощение, см. `roadmap.md`.
   **`GET /api/v1/orchestrator/robots-availability`** (`OrchestratorController`) отдаёт тот же самый
   снимок (`freeRobots`/`totalRobots`/`minFreeRobots`/`launchAllowed`) без побочных эффектов —
   `ExecutionService.getRobotAvailability()` переиспользуется и предусловием запуска (бросает
   `ConflictException`, если `!launchAllowed`), и этим read-only эндпоинтом. Предназначен для
   поллинга фронтом перед показом/разблокировкой кнопки запуска: `launchAllowed=false` в ответе
   этого эндпоинта гарантированно означает, что `POST .../run` в этот момент ответит `409` — оба
   пути читают ровно одно и то же условие, так что расхождения между "кнопка разблокирована" и
   "запуск неожиданно упал" быть не должно (если только доступность роботов не изменилась в
   промежутке между опросом и нажатием — сам по себе поллинг не резервирует роботов).
1. `POST /api/v1/scenarios/{id}/run` создаёт `ScenarioRun(status=PENDING)` и асинхронно (`@Async`)
   запускает `ScenarioExecutionEngine.execute(run)`. Тело запроса опционально принимает
   `startStepId` — id шага сценария (см. `StepResponse.id`), с которого начать обход, вместо
   корней DAG. Валидируется, что шаг принадлежит указанному сценарию (`InvalidRequestException`,
   если нет) и вообще существует (`NotFoundException`, если нет). Сохраняется на
   `ScenarioRun.start_step_id` и возвращается в `RunResponse` — видно, с чего именно был запущен
   конкретный прогон.
2. Движок находит корневые шаги DAG (без входящих рёбер) и исполняет их через `StepExecutor`
   (Strategy: `JobStepExecutor` / `QueueStepExecutor` / `QueueCheckStepExecutor`) — либо, если
   передан `startStepId`, использует этот единственный шаг как "корень" для этого конкретного
   прогона (`ScenarioExecutionEngine.runScenario(Long runId, Long startStepId)`). Шаги "до" точки
   старта по DAG движок не трогает вообще — они получают `StepRun(PENDING)` вместе со всеми
   остальными шагами сценария (см. "Наблюдаемость выполнения" ниже) и остаются в этом статусе
   навсегда, как и любой шаг, до которого обход не дошёл. **Ответственность за то, что предпосылки
   пропущенных шагов уже выполнены** (например, входная очередь уже наполнена нужными
   транзакциями предыдущим прогоном) **лежит на том, кто запускает** — движок это не проверяет.
   Типичный сценарий использования — перезапустить зависший/упавший `JOB` или переоценить
   `QUEUE_CHECK` без пересоздания уже готовых `QUEUE`-шагов заново.
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
   (`errorMsg` — туда пишется причина сбоя выполнения). Таймаут (только если задан `config.timeoutSeconds` шага `JOB`, глобального нет — ADR 0006)
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
   отфильтрованные по списку `naturalKey`) не достигнут ожидаемых из `config.expectedStatusCounts`
   / `config.minTotalCount`, либо не истечёт `config.timeoutSeconds` шага (не задан — без ограничения по времени, ADR 0006;
   падает только по досрочному выходу, ниже, либо ручной остановкой прогона).
   **`expectedStatusCounts` — это минимум по каждому статусу (`actual >= expected`), не точное
   совпадение.** Раньше проверялось строгое равенство, и лишняя транзакция сверх ожидания (или
   более позднее увеличение счётчика уже после того, как сценарий фактически выполнился так, как
   задумано) заставляла бы шаг упасть, хотя по сути своей ожидание автора сценария уже
   выполнилось — теперь `SUCCESS=3` означает "не меньше 3 успешных", а не "ровно 3".
   **Досрочный выход из поллинга, если ждать больше нечего** (Sprint 27): если задан непустой
   `naturalKeys` и все найденные по нему транзакции уже в конечном статусе (не `NEW`/`IN_PROGRESS`,
   с учётом `retray`/`maxRetray` — см. `effectiveStatus`), а проверка всё ещё не пройдена — их
   статус сам по себе больше не изменится, и шаг падает сразу, не ожидая оставшийся `timeout`
   впустую (было: ждали полный `timeout`, даже если результат был предрешён с первой же итерации).
   Не ноль-терпимость — нужно, чтобы ДВА опроса подряд показали одну и ту же картину (тот же набор,
   всё так же конечное), прежде чем падать: один подтверждающий опрос на случай, если набор вот-вот
   пополнится новой транзакцией с тем же ключом/префиксом. Без `naturalKeys` (проверка всей очереди
   через `minTotalCount`) это правило не действует — набор элементов не закрыт, новые могут
   появиться в любой момент, "все текущие уже финальны" ничего не доказывает. **Удалённые
   транзакции (`ExchangeQueueValueDto.deletedAt != null`) исключаются из выборки до подсчёта** —
   иначе удаление элемента из очереди (вручную или самим оркестратором) искажает и общий счётчик, и
   распределение по статусам, которое сравнивается с ожиданием сценария (`QueueAuditService`,
   ручной аудит из п. 4 `TESTING.md`, удалённые транзакции по-прежнему показывает — там это полезная
   информация для отладки, а не критерий прохождения проверки; ручной аудит по умолчанию (без явного
   `naturalKey` в запросе) тоже сужает список до naturalKey'ев, известных самому шагу —
   `QueueAuditService.deriveFilter` читает `ScenarioStep.config` (`transactions` для `QUEUE`,
   `naturalKeys`/`naturalKeyPrefixMatch` для `QUEUE_CHECK`), а не дампит всю очередь; явный
   `naturalKey` в запросе (`GET .../queue-items?naturalKey=...`) перекрывает автоматику. Полный
   постраничный обзор остаётся только когда сужать реально нечем (шаг без известных ключей, либо
   `scenario_step` уже удалён). Общая логика "перебор всей очереди vs. фильтр по ключам" живёт в
   одном месте — `QueueItemFinder` — и `QueueCheckStepExecutor`, и `QueueAuditService` зовут именно
   его, не свой код). **`ERROR`, для которого очередь ещё
   не исчерпала лимит повторов, не считается финальной ошибкой.** Оркестратор при статусе Error сам
   перекладывает транзакцию обратно в очередь на повторную попытку, пока `item.retray <
   queue.maxRetray` (оба поля приходят с оркестратором: `retray` — сколько повторов уже было у
   конкретной транзакции, `maxRetray` — лимит повторов, настроенный на самой очереди,
   `ExchangeQueueDto.maxRetray`). Пока лимит не исчерпан, `QueueCheckStepExecutor` считает такую
   транзакцию `IN_PROGRESS`, а не `ERROR` (`effectiveStatus`, в `countByStatus`) — иначе проверка
   зафиксировала бы "ошибку", которую оркестратор через мгновение сам исправит повторной попыткой.
   Фильтр по
   `naturalKeys` работает как точное совпадение по умолчанию (для входной очереди — мы сами знаем
   точные ключи отправленных транзакций) либо как совпадение по префиксу при
   `config.naturalKeyPrefixMatch=true` (для выходной очереди — базовый ключ сквозной от входа к
   выходу, но на выходе к нему может дописываться суффикс для трассировки при разветвлении одной
   входной транзакции на несколько выходных). Сценарий явно падает (`StepExecutionException` с
   текстом "ожидалось X, фактически Y"), если фактическое количество по хотя бы одному статусу из
   `expectedStatusCounts` за отведённое время так и не достигло ожидаемого минимума — например
   "ожидалось: ERROR>=0 BUSINESS_ERROR>=2 SUCCESS>=3 — фактически: всего=4 SUCCESS=4" (SUCCESS
   больше, чем минимум 3, — не ошибка; недостача по любому из статусов — ошибка). Очередь для проверки тоже
   идемпотентна (`ExchangeQueueProvisioner`) — если её ещё нет, создаётся пустой, и шаг просто
   ждёт появления элементов до таймаута, а не падает немедленно.
   **Поиск транзакций по `naturalKeys` идёт через фильтр оркестратора, не постраничным перебором
   всей очереди.** `GET /api/ExchangeQueues/v2/{id}/Items` поддерживает query-параметры
   `NaturalKey`/`NaturalKeyPart` (есть в `orc_swagger.json`, не задокументированы в `OrcService.java`/
   `orc_worker.py` — обнаружены только по схеме) — при непустом `naturalKeys` executor делает
   отдельный отфильтрованный запрос на каждый ключ (`QueueCheckStepExecutor.fetchByNaturalKeys`)
   вместо того, чтобы тянуть всю очередь (потенциально тысячи элементов) и фильтровать на своей
   стороне. Раньше это было не просто неэффективно, а откровенно ломало проверку: постраничный
   перебор ограничен `MAX_PAGES=50` (×`PAGE_SIZE=200` = 10 000 элементов) — если искомая транзакция
   лежала за этой границей, `QUEUE_CHECK` её попросту не находил, хотя она существовала. Точная
   семантика `NaturalKeyPart` на стороне оркестратора не подтверждена (возможно, это вхождение
   где угодно в строке, а не строго префикс) — поэтому после серверной фильтрации всё равно
   применяется собственная точная проверка (`startsWith`/точное равенство) как последний рубеж,
   так что даже более мягкий серверный фильтр не меняет результат проверки. Когда `naturalKeys` не
   задан (проверка только по `minTotalCount`/общим счётчикам), полный постраничный перебор
   сохраняется — там иначе никак, нужно посчитать всё содержимое очереди.
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
   последнего `ScenarioRun` и вызывает `DELETE` в оркестраторе для каждого. **Assignment удаляется
   всегда** (он всегда создаётся заново, см. п. 3), **очередь — только если её реально создал этот
   прогон** (`StepRun.orchestrator_queue_owned`, см. `ExchangeQueueProvisioner.Result.created()`):
   `QUEUE`-шаг мог просто переиспользовать уже существовавшую очередь (get-or-create) — она не
   наша, cleanup её не трогает; `QUEUE_CHECK` не владеет очередью никогда, даже если сам вызвал
   get-or-create и создал пустую (это шаг проверки, а не создания).

## Наблюдаемость выполнения (live-прогресс и логи)

Проблема, с которой пользователь столкнулся на реальном стенде: `GET /api/v1/runs/{runId}` до
этого показывал только `stepId`+`status`, а долгий шаг (ждёт робота в очереди проектов, выполняется
на роботе, поллит очередь) всё это время выглядел просто как `RUNNING` без каких-либо деталей — и
по логам сервера тоже было не восстановить, что происходит.

- **`StepRun.detail` / `detail_updated_at`** (`V5__step_run_detail.sql`) — свободный текст с
  текущей фазой шага, обновляемый на каждом значимом переходе (не только при смене статуса).
  Примеры значений на всём протяжении *одного* `JOB`-шага: `"Создаю задание 'My_Job_24_36' по
  проекту 'Sandbox Task'"` → `"Запускаю задание 'My_Job_24_36'"` → `"Задание 'My_Job_24_36' в
  очереди проектов оркестратора (поставлено ...), ожидание свободного робота (попытка #1)"` →
  `"Задание 'My_Job_24_36' завершилось на роботе 'robot-1': успешно"`. Для `QUEUE_CHECK` —
  `"Проверка очереди 'X' (попытка #7): всего=12 SUCCESS=10 ERROR=2, ожидается SUCCESS=10
  ERROR=2"` на каждой итерации поллинга, а не только в момент финального успеха/таймаута.
  **Везде используется человекочитаемое имя, а не голый numeric id оркестратора** — `JobStepExecutor`
  использует сгенерированное им самим имя Assignment (`_<runId>_<stepId>`, уникальное и
  осмысленное) вместо `orchestratorAssignmentId`, и передаёт это имя как "label" в
  `StatusPoller.pollUntilTerminal(stepRun, assignmentId, assignmentLabel)`, который использует его
  во всех своих сообщениях вместо `"id=" + assignmentId`. Имя проекта резолвится для отображения
  даже когда шаг сконфигурирован через `rpaProjectId` (не `rpaProjectName`) — best-effort, через
  `OrchestratorLookup.resolveProjectLabel` (если проект не резолвился, отображается `"id=N"`, но сам
  шаг не падает — имя нужно только для читаемости статуса). Raw id (`orchestratorAssignmentId`) при
  этом никуда не пропадает — он по-прежнему есть в `StepRunResponse` для программной работы с
  оркестратором, просто человекочитаемый `detail` его не использует.
  **`OrchestratorLookup`** (`execution/engine`) и **`OrchestratorNarration`** (`orchestrator/util`,
  ADR 0002) — канонические модули за этим: `OrchestratorLookup` делает вызовы к оркестратору
  (`resolveProjectId`/`resolveProjectLabel`/`findQueueEntries`, единственный владелец
  `RpaProjectsPort`/`RpaProjectQueuePort` для целей отображения), `OrchestratorNarration` — чистое
  форматирование уже полученных данных в текст (`describeRunning`/`describeQueued`/
  `describeQueueError`/`describeExpectation`/`describeActual`/`describeCheckResult`), без I/O.
  `StatusPoller`, `JobStepExecutor`, `QueueCheckStepExecutor` все идут через них — до Sprint 25
  каждый независимо собирал такой текст сам.
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
после); распараллеливать `queueIn`/`queueOut` между собой можно, а свести их в один общий дочерний
узел (fan-in) — можно и поддерживается: движок выполнит такой узел один раз, дождавшись успеха
обоих родителей (см. `agents.md`, раздел про fan-in).

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

## Аутентификация нашего API (не путать с "Аутентификация в оркестраторе" выше)

Раздел выше — как СЕРВИС логинится В оркестратор (исходящие вызовы). Этот раздел — кто может
вызывать НАШ API (см. ADR 0003, `docs/adr/0003-own-api-authentication-jwt-rbac.md`).

- **JWT stateless, токены — в `HttpOnly`-куках** (ADR 0004, не в JSON-теле — см. причину ниже).
  `POST /api/v1/auth/login` (логин/пароль) → access-токен (HS256, 15 мин, `JwtService`) +
  refresh-токен (7 дней), оба уходят клиенту через `Set-Cookie` (`AuthCookies`:
  `access_token` — `Path=/`, `refresh_token` — `Path=/api/v1/auth`; оба `HttpOnly`+`Secure`+
  `SameSite=Strict`). Тело ответа — только `{"expiresInSeconds": 900}`, сырых токенов там нет.
  `JwtAuthenticationFilter` проверяет access-токен на каждом запросе (куку, либо, fallback,
  `Authorization: Bearer` — для curl/скриптов/тестов) без обращения к БД — валиден по подписи.
  `POST /api/v1/auth/refresh`/`POST /api/v1/auth/logout` читают refresh-токен из куки, не из тела
  (ротация на `/refresh`: старый отзывается, выдаётся новый). Refresh-токен НЕ JWT — случайный
  токен, в БД (`refresh_token`) хранится только его SHA-256 хэш, не сам токен.
  **Требует HTTPS в проде/стейджинге** — `Secure`-куку браузер не отправит обратно по `http://` (см.
  `HTTPS_SETUP.md`); локально без TLS — `auth.cookies.secure=false`
  (`AUTH_COOKIES_SECURE`). CSRF-токены не добавлены — опора на `SameSite=Strict` (см. ADR 0004 для
  условий, при которых это решение нужно пересмотреть).
- **`GET /api/v1/auth/me`** (Sprint 28) — `{"username": "...", "role": "..."}` для текущего
  аутентифицированного пользователя. Единственный способ фронту узнать роль с тех пор, как токен
  ушёл в `HttpOnly`-куку (декодировать JWT на клиенте больше нельзя). Доступен любой
  аутентифицированной роли (не под конкретной ролью в матрице — это "кто я", не операция); роль
  достаётся из `GrantedAuthority` текущего `Authentication` (`SecurityContextHolder`), без похода в
  БД — `JwtAuthenticationFilter` уже положил её туда при разборе токена.
- **Роли — `ADMIN`/`OPERATOR`/`VIEWER`, одна на пользователя** (`app_user.role`); **права
  (`Permission`) выдаются ролям матрицей в БД, которую редактирует админ** (Sprint 29, ADR 0005).
  `SecurityConfig` знает только привязку "эндпоинт -> право", а "у роли есть право" спрашивает у
  `RolePermissionService` (кэш в памяти процесса, сбрасывается после коммита изменения; `ADMIN`
  всегда имеет все права и не редактируется — защита от самоблокировки). Всё, что не привязано к
  праву под `/api/v1/scenarios|runs|orchestrator|admin`, закрыто `denyAll()`:

  | Право | Эндпоинты | По умолчанию (миграция `V12`) |
  |---|---|---|
  | `SCENARIO_READ` | `GET /scenarios`, `GET /scenarios/{id}` | VIEWER, OPERATOR |
  | `SCENARIO_WRITE` | `POST /scenarios`, `PUT /scenarios/{id}` | OPERATOR |
  | `SCENARIO_DELETE` | `DELETE /scenarios/{id}` | — (только ADMIN) |
  | `RUN_READ` | `GET /runs/**` (прогон, `queue-items`) | VIEWER, OPERATOR |
  | `RUN_START` | `POST /scenarios/{id}/run` | OPERATOR |
  | `RUN_STOP` | `POST /runs/{id}/stop` | OPERATOR |
  | `CLEANUP` | `POST /scenarios/{id}/cleanup` | OPERATOR |
  | `ORCHESTRATOR_READ` | `GET /orchestrator/robots-availability` | VIEWER, OPERATOR |
  | `USER_MANAGE` | `/api/v1/admin/users/**` | — (только ADMIN) |
  | `ROLE_MANAGE` | `/api/v1/admin/roles/**`, `/api/v1/admin/permissions` | — (только ADMIN) |

  Управление матрицей — `RolePermissionController`: `GET /api/v1/admin/permissions` (справочник),
  `GET /api/v1/admin/roles` (текущая матрица, у `ADMIN` `editable=false`), `PUT
  /api/v1/admin/roles/{role}/permissions` (полная замена набора; для `ADMIN` — `400`). Новое право
  через API завести нельзя — оно привязывается к эндпоинту в коде. `GET /api/v1/auth/me` теперь
  возвращает и `permissions` текущей роли — UI-гейтинг делается по правам, не по имени роли.
- **Смена собственного пароля** — `POST /api/v1/auth/change-password` (`currentPassword`,
  `newPassword`), любая аутентифицированная роль, отдельное право не нужно. Требует текущий пароль
  (`400` если неверен или новый совпадает с текущим), отзывает ВСЕ refresh-токены пользователя
  (прочие сессии разлогинены), текущей сессии выдаёт новую пару кук — как login. В отличие от
  `PUT /api/v1/admin/users/{id}/password` (сброс админом без знания текущего пароля).
- **Пользователи — только через `AdminUserController`** (`/api/v1/admin/users`, `ADMIN`-only):
  создание, список, роль, `enabled`, сброс пароля. Нет self-registration и нет удаления —
  деактивация (`enabled=false`) вместо удаления (та же причина, что `ON DELETE SET NULL` для
  `scenario_run`/`step_run`: удаление осиротило бы `ScenarioRun.triggeredBy`). Первый `ADMIN`
  заводится `AdminBootstrapRunner` при старте, если `app_user` пуста, из
  `auth.bootstrap-admin.username`/`password` (env, не Flyway).
- **`ScenarioRun.triggeredBy`** теперь берётся из `Authentication.getName()` в `RunController`, не
  из тела запроса — клиент не может подставить чужое имя.
- Секрет подписи (`auth.jwt.secret`) и пароль бутстрап-админа — та же схема, что
  `orchestrator.credentials.*`: значение из окружения, шифруется Jasypt `ENC(...)` в проде.

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
| `orchestrator.polling.interval` | интервал опроса статуса Assignment (переопределяется `JOB.config.pollIntervalSeconds`). Глобального таймаута нет — только `JOB.config.timeoutSeconds` |
| `orchestrator.queue-check-polling.interval` | интервал опроса очереди в `QUEUE_CHECK` (по умолчанию для шагов без своего `pollIntervalSeconds`). Глобального таймаута нет — только `QUEUE_CHECK.config.timeoutSeconds` |
| `orchestrator.min-free-robots` | минимум свободных (Idle или Unavailable) роботов, при котором `POST .../run` вообще стартует прогон (иначе `409`), по умолчанию 2 |
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
