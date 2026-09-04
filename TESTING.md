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

## 2. Основной сценарий: Queue → Job → Queue → Job

Реальная модель работы (как она устроена в оркестраторе, а не в нашем API): проект, который
выполняет Job, сам знает (это настроено внутри проекта в Studio, мы это не контролируем и не
передаём при старте задания), из какой очереди читать входные транзакции и в какую очередь писать
результат. Наша роль — только создать входную очередь и положить в неё транзакции **до** старта
задания, дождаться, пока задание перейдёт в терминальный статус, и прочитать результат из
следующей очереди **после** этого — а имя очереди в `config.name` должно совпадать с тем, что
зашито в проекте.

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
    "description": "queue (input) -> job -> queue (output)",
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
        "nextLocalIds": ["job1"]
      },
      {
        "localId": "job1",
        "type": "JOB",
        "name": "First Job",
        "config": { "rpaProjectId": <RPA_PROJECT_ID_1> },
        "nextLocalIds": ["queueOut"]
      },
      {
        "localId": "queueOut",
        "type": "QUEUE",
        "name": "Output Queue",
        "config": { "name": "sandbox_output_queue" },
        "nextLocalIds": []
      }
    ]
  }'
```

Обратите внимание:
- `queueIn.config.name` и `queueOut.config.name` должны совпадать с именами очередей, которые
  реально настроены внутри `<RPA_PROJECT_ID_1>` в Studio — иначе задание не найдёт свою очередь
  или ничего в неё не запишет. Подставьте настоящие имена вместо `sandbox_input_queue`/`sandbox_output_queue`.
- `metadata` (`Dictionary<string,string>`) — это данные транзакции, которые реально читает проект;
  это не то же самое, что `arguments` в `config` job-шага (см. ниже) — те идут через
  `RpaProjectVariables` отдельным механизмом.
- `queueOut` создаётся **до** старта задания (это нужно, чтобы задание могло в неё писать), но
  транзакций в ней изначально нет — их создаст сам проект по ходу выполнения; мы читаем их только
  после того, как `job1` дойдёт до терминального статуса (раздел 4).

Сохраните `id` как `$SCENARIO_ID`, а из `steps[]` в ответе — `id` каждого шага (`$QUEUE_IN_STEP_ID`,
`$JOB_STEP_ID`, `$QUEUE_OUT_STEP_ID`).

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

### 2b. Автоматическая проверка результата (`QUEUE_CHECK`)

`AssignmentStatus.COMPLETE` означает только, что оркестратор успешно поставил проект в очередь
выполнения (`RpaProjectQueue`), а не то, что робот реально доделал работу — это подтверждённое
наблюдение с реального стенда. Единственный надёжный сигнал завершения — статус транзакций в
очереди, которую читает/пишет проект: `New → InProgress → (Success | Error | BusinessError)`.
Для этого есть отдельный тип шага `QUEUE_CHECK` — он не создаёт очередь, а ждёт, пока фактическое
распределение элементов по статусу не совпадёт с тем, что задал автор сценария, и явно проваливает
шаг (с деталями "ожидалось/фактически"), если распределение не сошлось за отведённое время.

Добавьте в сценарий из раздела 2 после `job1` перед `queueOut` проверку входной очереди — что
именно транзакция `tx-1` дошла до `Success` (замените на реально ожидаемый статус — `Error`/
`BusinessError`, если процесс должен был её забраковать):

```json
{
  "localId": "checkInput",
  "type": "QUEUE_CHECK",
  "name": "Проверка входной очереди",
  "config": {
    "queueName": "sandbox_input_queue",
    "naturalKeys": ["tx-1"],
    "expectedStatusCounts": { "SUCCESS": 1 },
    "timeoutSeconds": 120,
    "pollIntervalSeconds": 5
  },
  "nextLocalIds": ["queueOut"]
}
```
(и поменяйте `job1.nextLocalIds` на `["checkInput"]` вместо `["queueOut"]`)

Для выходной очереди базовый `naturalKey` сохраняется сквозным от входа к выходу (по вашему
описанию), но на выходе к нему может дописываться суффикс для трассировки, если одна входная
транзакция порождает несколько выходных — поэтому фильтр по `naturalKeys` тут работает как
**префикс** (`naturalKeyPrefixMatch: true`), а не точное совпадение:

```json
{
  "localId": "checkOutput",
  "type": "QUEUE_CHECK",
  "name": "Проверка выходной очереди",
  "config": {
    "queueName": "sandbox_output_queue",
    "naturalKeys": ["tx-1"],
    "naturalKeyPrefixMatch": true,
    "minTotalCount": 3,
    "expectedStatusCounts": { "SUCCESS": 3, "ERROR": 0, "BUSINESS_ERROR": 0 },
    "timeoutSeconds": 180
  },
  "nextLocalIds": []
}
```
Это найдёт `tx-1`, `tx-1-a`, `tx-1_dup2` и т.п. — всё, что начинается с `tx-1`, но не заденет
результаты других входных транзакций (`tx-2-...`) в той же очереди. Если базовый ключ результата
заранее неизвестен вообще (не ваш случай, но на будущее) — можно оставить `naturalKeys` пустым и
проверять только по `minTotalCount`/`expectedStatusCounts` без фильтра по ключам.

Проверка: если сознательно указать заведомо неверные ожидания (например, `"SUCCESS": 99`),
шаг должен упасть по таймауту `orchestrator.queue-check-polling.timeout` (по умолчанию 10 минут —
для теста задайте `timeoutSeconds` поменьше) с сообщением вида "Проверка очереди '...' не прошла
за отведённое время. Ожидалось: SUCCESS=99 — фактически: всего=1 SUCCESS=1 ...".

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
1. `queueIn` → `RUNNING` → `SUCCEEDED` (очередь и транзакция созданы **до** старта задания),
   у него появляется `orchestratorQueueId`.
2. `job1` → `RUNNING`, у него появляется `orchestratorAssignmentId`; проект читает `queueIn`
   изнутри — мы это не вызываем явно.
3. `job1` → `SUCCEEDED` (Assignment реально выполнился в оркестраторе и, если так настроено в
   проекте, записал результат в `queueOut`).
4. `queueOut` → `RUNNING` → `SUCCEEDED` (у нас очередь уже создана заранее, на этом шаге она
   просто подтверждается существующей — транзакций из нашего конфига в неё не добавляется).
5. Весь `ScenarioRun.status` → `SUCCEEDED`.

Если `queueIn` не создастся — `job1` не запустится вообще (родитель не `SUCCEEDED`, движок не
идёт дальше по DAG); аналогично если `job1` упадёт — `queueOut` не запустится.

## 4. Аудит результата в выходной очереди

```bash
curl -s "http://localhost:8080/api/v1/runs/$RUN_ID/steps/$QUEUE_OUT_STEP_ID/queue-items"
```
Здесь ожидаются транзакции, которые реально записал в `sandbox_output_queue` сам проект по ходу
выполнения (не то, что мы отправляли — мы её только создали пустой). Если пусто — либо проект ещё
не успел записать результат (подождите и повторите), либо имя очереди в `config.name` не совпадает
с тем, что реально использует проект.

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
`queueOut` не должен запуститься (родитель не `SUCCEEDED`).

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
шагов 3 и 5. Очереди (`queueIn`/`queueOut`) при этом создаются повторно с теми же именами — это
ожидаемо (см. раздел 9 про поведение `POST /api/ExchangeQueues` на уже существующее имя).

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

## 9. Повторное имя очереди при повторном запуске (непроверенный риск)

В отличие от Assignment (у которого имя каждый раз уникальное — `_<runId>_<stepId>`), очередь в
`QueueStepExecutor` создаётся **под тем же именем**, что задано в `config.name`, при каждом
прогоне (это осознанно — имя должно совпадать с тем, что зашито в проекте, а не быть уникальным).
Мы ни разу не проверяли на реальном стенде, как `POST /api/ExchangeQueues` реагирует на имя, под
которым очередь уже существует от предыдущего прогона (тем более если `cleanup` не вызывался).
Варианта два: либо оркестратор идемпотентно отдаёт `200` и переиспользует существующую очередь
(тогда всё ок как есть), либо возвращает ошибку конфликта имени (тогда нужно будет либо звать
`cleanup` перед каждым повторным запуском, либо делать `create` идемпотентным на нашей стороне —
проверять `findByName` до `create` и пропускать создание, если очередь уже есть).

Проверка: выполните раздел 3, затем **не вызывая cleanup**, повторите раздел 7 (или просто ещё
раз `POST /api/v1/scenarios/$SCENARIO_ID/run`) и посмотрите на статус `queueIn`/`queueOut` во
втором прогоне — `SUCCEEDED` (всё ок) или `FAILED` с ошибкой создания (нужна доработка, дайте
знать — поправим `QueueStepExecutor` на `create if not exists`).

## Чек-лист результата

- [ ] CRUD сценария работает (create/get/list/update/delete), защита от циклов — 400
- [ ] `queueIn` создаётся и наполняется транзакциями **до** старта `job1`
- [ ] `job1` создаётся, стартует, доходит до `SUCCEEDED`; аргументы (если заданы) применились
- [ ] `queueOut` создаётся до старта `job1`, после его завершения в ней видны транзакции,
      реально записанные проектом (раздел 4)
- [ ] Вариант с разветвлением (раздел 2a): обе Queue-ветки после Job запускаются параллельно
- [ ] Stop останавливает прогон и Assignment в оркестраторе, дочерние шаги не стартуют
- [ ] Cleanup удаляет Assignment/Queue именно последнего прогона
- [ ] Повторный запуск того же сценария (с ожиданием завершения предыдущего) проходит без
      конфликтов по имени Assignment; поведение с именем очереди — см. раздел 9
- [ ] Ошибка оркестратора (несуществующий проект и т.п.) даёт `FAILED` с понятным сообщением, а
      не падение сервиса
- [ ] Запуск **второго** Assignment того же `rpaProjectId`, пока первый ещё не завершён, даёт
      понятную ошибку (`501`, "запрещены повторы в очереди ожидания") — а не тихо виснет
- [ ] `QUEUE_CHECK` (раздел 2b) с верными ожиданиями проходит `SUCCEEDED`, с заведомо неверными —
      падает по таймауту с понятным "ожидалось/фактически" в `errorMessage`
