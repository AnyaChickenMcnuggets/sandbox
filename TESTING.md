# Ручной тестовый сценарий

Пошаговая проверка backend'а против реального оркестратора: от простого happy path (уже
подтверждён — job создаётся, стартует, завершается `SUCCEEDED`) до полного покрытия
функциональности — аргументов, очередей, параллельных веток, аудита, cleanup и повторного запуска.

Перед началом: сервис запущен (`http://localhost:8080`), `ORCHESTRATOR_*`/`JASYPT_ENCRYPTOR_PASSWORD`
заданы, миграции применились. Подставьте свои `<RPA_PROJECT_ID_1>`, `<RPA_PROJECT_ID_2>` — id
проектов в оркестраторе (можно любых, включая один и тот же дважды).

## 0. Смоук перед стартом

```bash
curl -s http://localhost:8080/actuator/health
```
Ожидается `{"status":"UP"}`.

## 1. CRUD сценария (без обращений к оркестратору)

```bash
curl -s -X POST http://localhost:8080/api/v1/scenarios \
  -H "Content-Type: application/json" \
  -d '{"name":"crud-check","description":"temp","steps":[
        {"localId":"j","type":"JOB","name":"J","config":{"rpaProjectId":1},"nextLocalIds":[]}
      ]}'
```
Сохраните `id` из ответа как `$CRUD_ID`, затем:

```bash
curl -s http://localhost:8080/api/v1/scenarios/$CRUD_ID
curl -s http://localhost:8080/api/v1/scenarios
curl -s -X PUT http://localhost:8080/api/v1/scenarios/$CRUD_ID \
  -H "Content-Type: application/json" \
  -d '{"name":"crud-check-renamed","description":"temp","steps":[
        {"localId":"j","type":"JOB","name":"J","config":{"rpaProjectId":1},"nextLocalIds":[]}
      ]}'
curl -s -X DELETE http://localhost:8080/api/v1/scenarios/$CRUD_ID -w "\n%{http_code}\n"
```
Ожидается: `200`/`204`, обновлённое имя в PUT-ответе, `204` на DELETE, дальнейший GET по этому id — `404`.

Также стоит проверить защиту от циклов — этот запрос должен вернуть `400`:
```bash
curl -s -X POST http://localhost:8080/api/v1/scenarios \
  -H "Content-Type: application/json" \
  -d '{"name":"cycle","steps":[
        {"localId":"a","type":"JOB","name":"A","config":{"rpaProjectId":1},"nextLocalIds":["b"]},
        {"localId":"b","type":"JOB","name":"B","config":{"rpaProjectId":1},"nextLocalIds":["a"]}
      ]}' -w "\n%{http_code}\n"
```

## 2. Основной сценарий: (Queue in, Queue out) → Job → Check → Check

Реальная модель работы (как она устроена в оркестраторе, а не в нашем API): проект, который
выполняет Job, сам знает (это настроено внутри проекта в Studio, мы это не контролируем и не
передаём при старте задания), из какой очереди читать входные транзакции и в какую очередь писать
результат. **Обе** очереди — и входная, и выходная — должны физически существовать в оркестраторе
**до** старта задания: проект не сможет ни прочитать ещё не созданную входную очередь, ни записать
результат в ещё не созданную выходную. Поэтому оба `QUEUE`-шага должны быть **предками** `JOB`-шага
в DAG (родитель/родитель-родителя, но не потомками) — ни в коем случае не наоборот.

> **Важное исправление:** в предыдущей версии этого файла `queueOut` был потомком `job1`
> (`job1 → queueOut`), с текстом "queueOut создаётся до старта задания" — то есть код противоречил
> собственному описанию. Это баг в документации, а не альтернативный правильный вариант — ниже
> исправлено: обе очереди идут **до** задания.

**Важно:** оркестратор не допускает второй одновременный запуск (`Start`) одного и того же
`rpaProjectId` — попытка вернёт `501` с сообщением про "запрещены повторы в очереди ожидания".
Это не ошибка нашего сервиса, а ограничение оркестратора: один проект = один активный запуск.
Если ниже используете свой `<RPA_PROJECT_ID_1>` повторно (шаги 5, 7) — дожидайтесь полного
завершения (`SUCCEEDED`/`FAILED`/`STOPPED`) предыдущего прогона перед следующим запуском, либо
используйте для параллельных/повторных тестов разные `rpaProjectId`.

```bash
curl -s -X POST http://localhost:8080/api/v1/scenarios \
  -H "Content-Type: application/json" \
  -d '{
    "name": "full-flow-test",
    "description": "queue (input) -> queue (output) -> job -> check input -> check output",
    "steps": [
      {
        "localId": "queueIn",
        "type": "QUEUE",
        "name": "Input Queue",
        "config": {
          "name": "sandbox_input_queue",
          "transactions": [
            {
              "naturalKey": "tx-1",
              "value": "payload-1",
              "metadata": {"source": "manual-test", "priority": "high"}
            }
          ]
        },
        "nextLocalIds": ["queueOut"]
      },
      {
        "localId": "queueOut",
        "type": "QUEUE",
        "name": "Output Queue",
        "config": { "name": "sandbox_output_queue" },
        "nextLocalIds": ["job1"]
      },
      {
        "localId": "job1",
        "type": "JOB",
        "name": "First Job",
        "config": { "rpaProjectId": <RPA_PROJECT_ID_1> },
        "nextLocalIds": ["checkInput"]
      },
      {
        "localId": "checkInput",
        "type": "QUEUE_CHECK",
        "name": "Check Input Queue Result",
        "config": {
          "queueName": "sandbox_input_queue",
          "naturalKeys": ["tx-1"],
          "expectedStatusCounts": { "SUCCESS": 1 },
          "timeoutSeconds": 120,
          "pollIntervalSeconds": 5
        },
        "nextLocalIds": ["checkOutput"]
      },
      {
        "localId": "checkOutput",
        "type": "QUEUE_CHECK",
        "name": "Check Output Queue Result",
        "config": {
          "queueName": "sandbox_output_queue",
          "naturalKeys": ["tx-1"],
          "naturalKeyPrefixMatch": true,
          "minTotalCount": 1,
          "timeoutSeconds": 120
        },
        "nextLocalIds": []
      }
    ]
  }'
```

`queueIn → queueOut` здесь — это просто "оба до job1", очерёдность между ними не важна (нет
данных-зависимости друг от друга); последовательно, а не параллельными корнями — чтобы не
усложнять пример, движок и так исполнит их за секунды. `job1` стартует только после того, как
**оба** предка дошли до `SUCCEEDED`.

Обратите внимание:
- Вместо `"rpaProjectId": <RPA_PROJECT_ID_1>` в `job1.config` можно указать
  `"rpaProjectName": "Точное название проекта"` — бэкенд сам найдёт id через `GET
  /api/RpaProjects/v3/short` (`RpaProjectsPort.findByName`, точное совпадение имени; если есть
  несколько версий с одинаковым именем — берётся активная). Если имя не найдено — шаг падает сразу,
  до создания Assignment, с сообщением вида "Проект '...' не найден в оркестраторе". Указывать
  нужно ровно одно — либо `rpaProjectName`, либо `rpaProjectId`.
- `queueIn.config.name` и `queueOut.config.name` должны совпадать с именами очередей, которые
  реально настроены внутри `<RPA_PROJECT_ID_1>` в Studio — иначе задание не найдёт свою очередь
  или ничего в неё не запишет. Подставьте настоящие имена вместо `sandbox_input_queue`/`sandbox_output_queue`.
- `metadata` (`Dictionary<string,string>`) — это данные транзакции, которые реально читает проект;
  это не то же самое, что `arguments` в `config` job-шага (см. ниже) — те идут через
  `RpaProjectVariables` отдельным механизмом.
- `job1` переходит в `SUCCEEDED` только когда реально завершится на роботе: движок ждёт запись в
  `GET /api/RpaProjectLaunches/assignment/{id}` с `completedAt`/`killedAt` и `success=true` — не
  просто `AssignmentStatus.Complete` (тот наступает почти сразу после `Start`, задолго до того как
  робот возьмётся за работу, и сам по себе больше не считается признаком успеха). `checkInput`/
  `checkOutput` при этом не про "запустилось ли задание вообще" — это уже гарантировано `job1`
  — а про бизнес-результат: сколько именно транзакций получили какой статус (полезно, когда важно
  не просто "как-то отработало", а "именно 3 Success и 0 Error").

Сохраните `id` как `$SCENARIO_ID`, а из `steps[]` в ответе — `id` каждого шага (`$QUEUE_IN_STEP_ID`,
`$QUEUE_OUT_STEP_ID`, `$JOB_STEP_ID`, `$CHECK_INPUT_STEP_ID`, `$CHECK_OUTPUT_STEP_ID`).

Если вместо/вместе с очередью хотите проверить именно project-переменные (`arguments`) —
добавьте в `config` job-шага `"arguments": {"имя_переменной": "значение"}` и после прогона
сверьте значение в UI/API оркестратора (`GET /api/RpaProjectVariables/Assignment/{assignmentId}`).

### 2a. Вариант с разветвлением (после Job — сразу две очереди)

Отдельно проверяет параллельное исполнение (изначальное требование "разветвление сценария на
две очереди после определённого задания") — здесь `job1` не читает никакую нашу очередь, а сразу
после его завершения параллельно создаются и заполняются две независимые очереди:

```json
{
  "name": "fan-out-test",
  "steps": [
    { "localId": "job1", "type": "JOB", "name": "Job",
      "config": { "rpaProjectId": <RPA_PROJECT_ID_2> }, "nextLocalIds": ["queueA", "queueB"] },
    { "localId": "queueA", "type": "QUEUE", "name": "Queue A",
      "config": { "name": "sandbox_queue_a", "transactions": [{"naturalKey": "a-1", "value": "v"}] },
      "nextLocalIds": [] },
    { "localId": "queueB", "type": "QUEUE", "name": "Queue B",
      "config": { "name": "sandbox_queue_b", "transactions": [{"naturalKey": "b-1", "value": "v"}] },
      "nextLocalIds": [] }
  ]
}
```
Используйте **другой** `<RPA_PROJECT_ID_2>` (не тот же, что в основном сценарии), чтобы не
столкнуться с ограничением "один активный запуск на проект", если гоняете оба сценария подряд.

### 2b. Про `checkInput`/`checkOutput` из раздела 2

> Если раньше `checkInput`/`checkOutput` зависали в `RUNNING` и не показывались в списке шагов, а
> прогон не завершался — это была починенная ошибка: `GET /api/ExchangeQueues/{id}/Items` (без
> версии) тихо отдавал не тот формат ответа, `QUEUE_CHECK` не видел элементов и поллил до
> таймаута. Исправлено на `v2`-эндпоинт (см. `roadmap.md`, Sprint 11) — повторите прогон.

`checkInput` и `checkOutput` в основном сценарии (раздел 2) — это автоматическая проверка
**бизнес-результата** (`QUEUE_CHECK`), а не признак того, что задание вообще выполнилось (это уже
гарантирует сам `job1` — см. раздел 3). Каждый ждёт, пока фактическое распределение элементов
очереди по статусу не совпадёт с тем, что задал автор сценария, и явно проваливает шаг (с деталями
"ожидалось/фактически"), если распределение не сошлось за отведённое время
(`orchestrator.queue-check-polling.timeout` по умолчанию 10 минут, либо `config.timeoutSeconds`).

- `checkInput` проверяет **точным** совпадением по `naturalKeys` (мы сами знаем, что клали `tx-1`
  во входную очередь).
- `checkOutput` проверяет **по префиксу** (`naturalKeyPrefixMatch: true`) — базовый `naturalKey`
  сохраняется сквозным от входа к выходу, но на выходе к нему может дописываться суффикс для
  трассировки, если одна входная транзакция порождает несколько выходных. `tx-1` в режиме префикса
  найдёт `tx-1`, `tx-1-a`, `tx-1_dup2` и т.п., но не заденет результаты других входных транзакций
  (`tx-2-...`) в той же очереди.

Проверка на заведомо неверные ожидания: временно поменяйте в `checkInput.config` значение
`expectedStatusCounts` на `{"SUCCESS": 99}` и пересоздайте сценарий — шаг должен упасть по
таймауту с сообщением вида "Проверка очереди '...' не прошла за отведённое время. Ожидалось:
SUCCESS=99 — фактически: всего=1 SUCCESS=1 ...". Верните `1` обратно после проверки.

## 3. Запуск и контроль в реальном времени

```bash
curl -s -X POST http://localhost:8080/api/v1/scenarios/$SCENARIO_ID/run
```
Сохраните `id` ответа как `$RUN_ID` (статус — `PENDING`, затем движок переводит в `RUNNING`).

Поллинг статуса (как это будет делать реальный клиент):
```bash
watch -n 3 "curl -s http://localhost:8080/api/v1/runs/$RUN_ID"
```
(или без `watch` — просто повторяйте `curl` каждые несколько секунд)

Ожидаемая последовательность:
1. `queueIn` → `RUNNING` → `SUCCEEDED` (очередь и транзакция созданы, или переиспользована уже
   существующая — см. раздел 9), у него появляется `orchestratorQueueId`.
2. `queueOut` → `RUNNING` → `SUCCEEDED` (создаётся пустой — заданию будет куда писать), у него
   тоже появляется `orchestratorQueueId`. Только после этого стартует `job1` — оба предка должны
   быть `SUCCEEDED`.
3. `job1` → `RUNNING`, у него появляется `orchestratorAssignmentId`; проект читает `queueIn` и
   пишет в `queueOut` изнутри — мы это не вызываем явно. Здесь же движок опрашивает **не**
   `AssignmentStatus`, а `GET /api/RpaProjectLaunches/assignment/{id}` — реальные запуски проекта
   на роботах. Пока записи нет — задание либо ещё в очереди проектов (робот не подхватил), либо
   уже выполняется, но не завершилось; это может занять заметно больше времени, чем раньше
   казалось по `AssignmentStatus.Complete`.
4. `job1` → `SUCCEEDED` **только когда в `RpaProjectLaunches` появится запись с
   `completedAt`/`killedAt` и `success=true`** — то есть когда робот реально отработал. Если
   `success=false` — шаг сразу `FAILED`, а `errorMessage` дополнительно подтягивает `errorMsg` из
   `RpaProjectQueue`, если оркестратор его туда записал. Дальше движок идёт к `checkInput`.
5. `checkInput` → `RUNNING` (поллит `queueIn`, пока `tx-1` не дойдёт до
   `Success`/`Error`/`BusinessError`, либо не истечёт `timeoutSeconds`) → `SUCCEEDED`, когда
   реальный статус транзакции совпал с `expectedStatusCounts`. Обычно это уже мгновенно, так как
   `job1` дожидается реального завершения сам — `checkInput` тут скорее подтверждает бизнес-исход
   (например, что конкретно `tx-1` дошла до `Success`, а не `BusinessError`).
6. `checkOutput` → аналогично поллит `queueOut` на появление транзакций с префиксом `tx-1` →
   `SUCCEEDED`.
7. Весь `ScenarioRun.status` → `SUCCEEDED`.

Если задание зависнет надолго (никто не берёт в работу или робот не отвечает), `job1` в итоге
упадёт по таймауту `orchestrator.polling.timeout` (по умолчанию 30 минут) с одним из трёх текстов
в `errorMessage`: "всё ещё в очереди проектов" (никакой робот не подхватил), "запущено на роботе
'X' ..., но так и не завершилось" (взял в работу, но завис), либо "не найдено ни в очереди
проектов, ни среди запусков" (неожиданная ситуация — стоит разбираться на стороне оркестратора).

Если `queueIn`/`queueOut` не создадутся — `job1` не запустится вообще (родитель не `SUCCEEDED`,
движок не идёт дальше по DAG); если `job1` упадёт — `checkInput`/`checkOutput` не запустятся;
если реальные статусы транзакций не совпадут с ожиданиями — упадёт `checkInput` или `checkOutput`,
даже если `job1` был `SUCCEEDED`.

## 4. Аудит результата в выходной очереди (вручную, в дополнение к `checkOutput`)

```bash
curl -s "http://localhost:8080/api/v1/runs/$RUN_ID/steps/$QUEUE_OUT_STEP_ID/queue-items"
```
Здесь ожидаются транзакции, которые реально записал в `sandbox_output_queue` сам проект по ходу
выполнения (не то, что мы отправляли — мы её только создали пустой). Если пусто — либо проект ещё
не успел записать результат (подождите и повторите), либо имя очереди в `config.name` не совпадает
с тем, что реально использует проект. `checkOutput` (раздел 2b) делает то же самое автоматически
и с ожиданием, но этот ручной вызов удобен для отладки, если `checkOutput` неожиданно падает.

## 5. Остановка прогона (на новом запуске)

**Дождитесь, пока прогон из раздела 3 дойдёт до `SUCCEEDED`/`FAILED`** (см. предупреждение в
разделе 2 — иначе получите `501`/"запрещены повторы в очереди ожидания" от того же `rpaProjectId`).
Затем запустите сценарий ещё раз и сразу остановите, пока `job1` в `RUNNING`:
```bash
curl -s -X POST http://localhost:8080/api/v1/scenarios/$SCENARIO_ID/run   # новый $RUN_ID_2
curl -s -X POST http://localhost:8080/api/v1/runs/$RUN_ID_2/stop
```
Ожидается: `ScenarioRun.status` → `STOPPED`, у `job1` — `FAILED` с `errorMessage`
"Остановлено пользователем", в оркестраторе вызван `PUT /api/Assignments/{id}/Stop`.
`checkInput`/`checkOutput` не должны запуститься (родитель не `SUCCEEDED`).

## 6. Cleanup

```bash
curl -s -X POST http://localhost:8080/api/v1/scenarios/$SCENARIO_ID/cleanup
```
Ожидается `{"success":true,"failures":[]}` для **последнего** прогона (того, что из шага 5 —
`$RUN_ID_2`). Проверьте в оркестраторе: Assignment и обе очереди из этого прогона удалены.
(Прогон из шага 3 при этом уже "устарел" — cleanup удаляет только сущности последнего прогона,
это ожидаемое поведение.)

## 7. Повторный запуск "по одной кнопке"

Дождитесь завершения прогона из раздела 5 (`STOPPED` — уже терминальный статус, можно сразу),
затем без каких-либо изменений просто ещё раз:
```bash
curl -s -X POST http://localhost:8080/api/v1/scenarios/$SCENARIO_ID/run
```
Это и есть основной сценарий использования: сценарий сохранён один раз, повторные прогоны создают
новые Assignment с уникальными именами (`_<runId>_<stepId>`) без конфликтов с предыдущими
прогонами — проверьте, что новый прогон снова доходит до `SUCCEEDED` независимо от прогонов из
шагов 3 и 5. Очереди (`queueIn`/`queueOut`) при этом не пересоздаются — переиспользуются
существующие под теми же именами (см. раздел 9).

## 8. Обработка ошибок оркестратора

Проверка, что ошибки оркестратора не роняют сервис молча:
```bash
curl -s -X POST http://localhost:8080/api/v1/scenarios \
  -H "Content-Type: application/json" \
  -d '{"name":"bad-project","steps":[
        {"localId":"j","type":"JOB","name":"J","config":{"rpaProjectId":999999999},"nextLocalIds":[]}
      ]}'
# сохраните id как $BAD_SCENARIO_ID
curl -s -X POST http://localhost:8080/api/v1/scenarios/$BAD_SCENARIO_ID/run
# подождите и проверьте статус:
curl -s http://localhost:8080/api/v1/runs/<полученный runId>
```
Ожидается: шаг переходит в `FAILED` с осмысленным `errorMessage` (ошибка от оркестратора,
например "проект не найден"), `ScenarioRun.status` → `FAILED`, сервис не падает и продолжает
отвечать на другие запросы.

## 9. Повторное имя очереди при повторном запуске

В отличие от Assignment (у которого имя каждый раз уникальное — `_<runId>_<stepId>`), очередь
создаётся **под тем же именем**, что задано в `config.name`, при каждом прогоне (это осознанно —
имя должно совпадать с тем, что зашито в проекте, а не быть уникальным). `QueueStepExecutor` и
`QUEUE_CHECK` теперь используют `ExchangeQueueProvisioner` (find-or-create): сначала ищут очередь
по имени и переиспользуют, если она уже есть, и только при отсутствии зовут `POST
/api/ExchangeQueues` — повторный прогон без `cleanup` больше не должен падать на конфликте имени.

Проверка: выполните раздел 3, затем **не вызывая cleanup**, повторите раздел 7 (или просто ещё
раз `POST /api/v1/scenarios/$SCENARIO_ID/run`) и убедитесь, что `queueIn`/`queueOut` во втором
прогоне снова `SUCCEEDED` (не `FAILED` с ошибкой создания) — это подтверждает идемпотентность
на реальном стенде, не только в unit-тестах.

## Чек-лист результата

- [ ] CRUD сценария работает (create/get/list/update/delete), защита от циклов — 400
- [ ] `queueIn` и `queueOut` — **оба** создаются до старта `job1` (оба его предки в DAG)
- [ ] `job1` доходит до `SUCCEEDED` **только** когда в `RpaProjectLaunches` реально появилась
      завершённая запись (`completedAt`+`success=true`) — не раньше; аргументы (если заданы)
      применились
- [ ] `job1` с заведомо провальным прогоном (`success=false` на роботе) даёт `FAILED` с текстом
      ошибки, желательно с `errorMsg` из `RpaProjectQueue`, а не просто "Задание завершилось..."
      без деталей
- [ ] `checkInput`/`checkOutput` (после `job1`) подтверждают именно бизнес-исход (точные
      статусы/количества транзакций), а не сам факт запуска — это уже гарантирует `job1`
- [ ] Вариант с разветвлением (раздел 2a): обе Queue-ветки после Job запускаются параллельно
- [ ] Stop останавливает прогон и Assignment в оркестраторе, дочерние шаги не стартуют
- [ ] Cleanup удаляет Assignment/Queue именно последнего прогона
- [ ] Повторный запуск того же сценария (с ожиданием завершения предыдущего) проходит без
      конфликтов по имени Assignment; очереди переиспользуются, а не падают на конфликте имени —
      см. раздел 9
- [ ] Ошибка оркестратора (несуществующий проект и т.п.) даёт `FAILED` с понятным сообщением, а
      не падение сервиса
- [ ] Запуск **второго** Assignment того же `rpaProjectId`, пока первый ещё не завершён, даёт
      понятную ошибку (`501`, "запрещены повторы в очереди ожидания") — а не тихо виснет
- [ ] `QUEUE_CHECK` (раздел 2b) с верными ожиданиями проходит `SUCCEEDED`, с заведомо неверными —
      падает по таймауту с понятным "ожидалось/фактически" в `errorMessage`
- [ ] `job1.config.rpaProjectName` с реальным именем проекта резолвится в тот же `rpaProjectId`,
      что и явное указание id; с несуществующим именем — падает сразу с понятным сообщением, не
      создавая Assignment
