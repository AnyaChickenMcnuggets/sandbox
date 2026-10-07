# Ручной тестовый сценарий

Пошаговая проверка backend'а против реального оркестратора: от простого happy path (уже
подтверждён — job создаётся, стартует, завершается `SUCCEEDED`) до полного покрытия
функциональности — аргументов, очередей, параллельных веток, аудита, cleanup и повторного запуска.

Перед началом: сервис запущен (`http://localhost:8080`), `ORCHESTRATOR_*`/`JASYPT_ENCRYPTOR_PASSWORD`
заданы, миграции применились. Подставьте свои `<RPA_PROJECT_ID_1>`, `<RPA_PROJECT_ID_2>` — id
проектов в оркестраторе (можно любых, включая один и тот же дважды).

**С Sprint 26 весь API, кроме `/actuator/health` и `/api/v1/auth/*`, требует аутентификации.** С
Sprint 27 токены лежат в `HttpOnly`-куках, не в теле ответа (ADR 0004) — curl получает их через
`-c <файл>` (сохранить куки из ответа) и прикладывает через `-b <файл>` (отправить куки). Используйте
отдельный файл-куки на роль, когда нужно сравнивать поведение нескольких ролей одновременно (раздел
0b) — `-c`/`-b` на один и тот же файл перезатирают его на каждый логин. Ниже подразумевается файл
`cookies.txt`, добавляйте `-b cookies.txt` ко ВСЕМ командам после `0a` (в примерах не повторяется
ради краткости). Access-токен в куке живёт 15 минут; если команды из этого файла выполняются
дольше — `POST /api/v1/auth/refresh -b cookies.txt -c cookies.txt` обновит куки на месте (ротация),
либо залогиньтесь заново. По умолчанию (`auth.cookies.secure=true`) кука `Secure` — curl по
`http://localhost` её всё равно примет и пришлёт назад (curl не проверяет `Secure` так строго, как
браузер), но у РЕАЛЬНОГО браузерного фронта `Secure`-кука без HTTPS работать не будет — см.
`HTTPS_SETUP.md`.

## 0. Смоук перед стартом

```bash
curl -s http://localhost:8080/actuator/health
```
Ожидается `{"status":"UP"}` (это единственный эндпоинт, не требующий токена, — health-check для
инфраструктуры).

## 0a. Первый ADMIN и логин

Если `app_user` ещё пуста, `AdminBootstrapRunner` завёл ADMIN при старте из
`AUTH_BOOTSTRAP_ADMIN_USERNAME`/`AUTH_BOOTSTRAP_ADMIN_PASSWORD` (см. `application.yml`,
`auth.bootstrap-admin.*`) — если пароль не был задан, в логе при старте предупреждение "первый
ADMIN не создан", тогда войти будет некому: задайте переменную и перезапустите сервис.

```bash
curl -s -c cookies.txt -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"<пароль из AUTH_BOOTSTRAP_ADMIN_PASSWORD>"}'
```
Ожидается `200` и тело `{"expiresInSeconds":900}` — токенов в теле больше нет, они ушли в
`Set-Cookie` и сохранены в `cookies.txt` (`-c`). Проверить, что куки реально сохранились:
```bash
grep -E "access_token|refresh_token" cookies.txt
```
Неверный пароль → `401` с `{"code":"INVALID_CREDENTIALS", ...}`.

Узнать, под кем залогинены (и какая роль — фронт использует это для UI-гейтинга, Sprint 28):
```bash
curl -s -b cookies.txt http://localhost:8080/api/v1/auth/me
```
Ожидается `200`, `{"username":"admin","role":"ADMIN"}`. Без куки (или с протухшей) — `401`. Любая
роль, не только `ADMIN`, должна получать `200` на этот эндпоинт — попробуйте тем же `-b` с
файлом-кукой от `VIEWER`-пользователя (раздел 0b), тоже `200`, просто `"role":"VIEWER"`.

Дальше во всех примерах файла — `-b cookies.txt` там, где нужен доступ под этим пользователем.

## 0b. Роли и матрица доступа

Заведите по одному пользователю каждой роли через admin API (раздел 0c ниже), залогиньте каждого в
СВОЙ файл-куки (`-c cookies-viewer.txt`, `-c cookies-operator.txt`, `-c cookies-admin.txt`), затем
проверьте матрицу из `architecture.md`/ADR 0003 — например:
```bash
curl -s -o /dev/null -w "%{http_code}\n" -X POST http://localhost:8080/api/v1/scenarios/1/run \
  -b cookies-viewer.txt
```
Ожидается `403`. С `cookies-operator.txt` тот же запрос — `202` (или `404`/`409`, если сценария с
таким id нет/уже выполняется — важно, что не `403`). `DELETE /api/v1/scenarios/{id}` с
`cookies-operator.txt` — `403`, с `cookies-admin.txt` — `204`.

## 0c. Admin API (управление пользователями)

Только `ADMIN` (`-b cookies-admin.txt` или `cookies.txt` из 0a, если логинились под admin). Создание:
```bash
curl -s -b cookies.txt -X POST http://localhost:8080/api/v1/admin/users \
  -H "Content-Type: application/json" \
  -d '{"username":"operator1","password":"password123","role":"OPERATOR"}'
```
Сохраните `id` из ответа как `$USER_ID`. Ожидается `201`, тело без `passwordHash`. Повторный
`POST` с тем же `username` → `409 CONFLICT`. Пароль короче 8 символов → `400`.

```bash
curl -s -b cookies.txt http://localhost:8080/api/v1/admin/users
curl -s -b cookies.txt http://localhost:8080/api/v1/admin/users/$USER_ID
curl -s -b cookies.txt -X PUT http://localhost:8080/api/v1/admin/users/$USER_ID/role \
  -H "Content-Type: application/json" -d '{"role":"ADMIN"}'
curl -s -b cookies.txt -X PUT http://localhost:8080/api/v1/admin/users/$USER_ID/enabled \
  -H "Content-Type: application/json" -d '{"enabled":false}'
```
После `enabled:false` — залогиньтесь под `operator1` в отдельный `cookies-operator.txt` (раздел 0a)
и убедитесь, что `401`, и что его refresh-кука (если он успел залогиниться раньше отключения) тоже
больше не работает (`POST /api/v1/auth/refresh -b cookies-operator.txt` → `401`) — деактивация
обязана отзывать активные refresh-токены, не только блокировать будущий логин.

Сброс пароля:
```bash
curl -s -b cookies.txt -X PUT http://localhost:8080/api/v1/admin/users/$USER_ID/password \
  -H "Content-Type: application/json" -d '{"newPassword":"newpass1234"}'
```
Ожидается `204`; старый пароль для этого пользователя перестаёт работать на `/auth/login`.

## 0d. Refresh и logout

```bash
curl -s -b cookies.txt -c cookies.txt -X POST http://localhost:8080/api/v1/auth/refresh
```
`-b` отправляет текущий refresh из куки, `-c` перезаписывает файл новой парой (ротация). Ожидается
`200`, `{"expiresInSeconds":900}`. Повторный вызов с файлом-копией, снятой ДО этого рефреша (т.е. со
старым, уже использованным refresh-токеном), должен дать `401` — ротация отзывает refresh-токен
сразу при использовании:
```bash
cp cookies.txt cookies-before-refresh.txt   # снять копию перед следующим refresh, для этой проверки
curl -s -b cookies.txt -c cookies.txt -X POST http://localhost:8080/api/v1/auth/refresh
curl -s -o /dev/null -w "%{http_code}\n" -b cookies-before-refresh.txt \
  -X POST http://localhost:8080/api/v1/auth/refresh   # ожидается 401 — токен уже заменён
```

```bash
curl -s -b cookies.txt -X POST http://localhost:8080/api/v1/auth/logout -w "\n%{http_code}\n"
```
Ожидается `204`; повторный `refresh` с `cookies.txt` после `logout` — `401`.

## 0e. Смена своего пароля (Sprint 29)

Любая аутентифицированная роль, отдельное право не нужно. Под любым залогиненным пользователем:
```bash
curl -s -b cookies.txt -c cookies.txt -X POST http://localhost:8080/api/v1/auth/change-password   -H "Content-Type: application/json"   -d '{"currentPassword":"<текущий>","newPassword":"newpass12345"}'
```
Ожидается `200`, `{"expiresInSeconds":900}`, в `cookies.txt` новая пара кук (`-c`). Проверки:
- неверный `currentPassword` → `400`, `code: "INVALID_REQUEST"`, пароль не изменился;
- `newPassword` равен текущему → `400`; короче 8 символов → `400` (`VALIDATION_FAILED`);
- без куки → `401`;
- старый пароль на `/auth/login` больше не работает, новый — работает;
- ДРУГАЯ сессия того же пользователя (второй файл-куки, залогиненный раньше смены) после смены:
  `POST /api/v1/auth/refresh -b <тот файл>` → `401` (все refresh-токены отозваны), а сессия, из
  которой меняли пароль, продолжает работать (ей выдана новая пара).

## 0f. Матрица ролей и прав (Sprint 29, ADR 0005)

Только право `ROLE_MANAGE` (по умолчанию только `ADMIN`; `-b cookies.txt` под admin):
```bash
curl -s -b cookies.txt http://localhost:8080/api/v1/admin/permissions
curl -s -b cookies.txt http://localhost:8080/api/v1/admin/roles
```
Ожидается: справочник из 10 прав с описаниями; матрица из трёх ролей, у `ADMIN` `editable:false` и
все права, у `OPERATOR`/`VIEWER` — стартовый набор (как в `architecture.md`).

Выдать роли `VIEWER` право запуска и проверить, что работает **сразу, без перелогина**:
```bash
curl -s -b cookies.txt -X PUT http://localhost:8080/api/v1/admin/roles/VIEWER/permissions   -H "Content-Type: application/json"   -d '{"permissions":["SCENARIO_READ","RUN_READ","ORCHESTRATOR_READ","RUN_START"]}'
curl -s -o /dev/null -w "%{http_code}
" -b cookies-viewer.txt -X POST http://localhost:8080/api/v1/scenarios/1/run
```
Второй запрос (под `VIEWER`) раньше давал `403`, теперь не `403` (`202`/`404`/`409`). Верните набор
обратно (без `RUN_START`) — снова `403`. Также проверьте:
- `PUT .../roles/ADMIN/permissions` → `400` (права `ADMIN` не редактируются);
- неизвестный код права в теле (`"MAKE_COFFEE"`) → `400`; отсутствует поле `permissions` → `400`;
- `PUT .../roles/OPERATOR/permissions` с `{"permissions":[]}` → `200`, роль остаётся без прав;
  `GET /api/v1/auth/me` под `OPERATOR` отдаёт `permissions: []`, но `200` (не `403`);
- `GET /api/v1/auth/me` под любой ролью показывает актуальные `permissions` после правки матрицы;
- выдача `OPERATOR` права `USER_MANAGE` открывает `/api/v1/admin/users`, но НЕ `/api/v1/admin/roles`
  (это отдельное `ROLE_MANAGE`).

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
гарантирует сам `job1` — см. раздел 3). Каждый ждёт, пока фактическое количество элементов очереди
по каждому статусу из `expectedStatusCounts` не достигнет заданного минимума (`actual >=
expected`, не точное совпадение — больше ожидаемого тоже считается пройденной проверкой), и явно
проваливает шаг (с деталями "ожидалось/фактически"), если минимум по хотя бы одному статусу не
достигнут за отведённое время — ТОЛЬКО если задан `config.timeoutSeconds` шага (глобального
таймаута нет, ADR 0006; не задан — шаг ждёт без ограничения по времени, либо пока не сработает
досрочный выход ниже, либо прогон не остановят вручную).

- `checkInput` проверяет **точным** совпадением по `naturalKeys` (мы сами знаем, что клали `tx-1`
  во входную очередь).
- `checkOutput` проверяет **по префиксу** (`naturalKeyPrefixMatch: true`) — базовый `naturalKey`
  сохраняется сквозным от входа к выходу, но на выходе к нему может дописываться суффикс для
  трассировки, если одна входная транзакция порождает несколько выходных. `tx-1` в режиме префикса
  найдёт `tx-1`, `tx-1-a`, `tx-1_dup2` и т.п., но не заденет результаты других входных транзакций
  (`tx-2-...`) в той же очереди.

Проверка на заведомо недостижимые ожидания: временно поменяйте в `checkInput.config` значение
`expectedStatusCounts` на `{"SUCCESS": 99}` и пересоздайте сценарий — шаг должен упасть по
таймауту с сообщением вида "Проверка очереди '...' не прошла за отведённое время. Ожидалось:
SUCCESS>=99 — фактически: всего=1 SUCCESS=1 ...". Верните `1` обратно после проверки.

Проверка новой семантики "минимум, а не точное совпадение" (Sprint 22): поставьте
`expectedStatusCounts` заведомо **меньше** фактического количества (например `{"SUCCESS": 0}`,
если реально успешных транзакций больше) — шаг должен пройти `SUCCEEDED`, а не упасть из-за того,
что фактическое количество "не совпало" с ожидаемым. До Sprint 22 это было строгое равенство, и
лишняя транзакция сверх ожидания роняла бы проверку.

Проверка досрочного выхода (Sprint 27): укажите в `checkInput.config` заведомо недостижимое
`expectedStatusCounts` (как в проверке выше, `{"SUCCESS": 99}`) ПРИ этом с непустым `naturalKeys`
(раздел 2, `checkInput` уже проверяет точным совпадением по `naturalKeys` — так и оставьте). Если
`tx-1` реально уже в конечном статусе (`SUCCESS`/`ERROR` без оставшихся повторов) — шаг должен
упасть сразу после одного подтверждающего опроса — не дожидаясь `timeoutSeconds` (и без
`timeoutSeconds` тоже, зависания нет): в сообщении об ошибке вместо "не прошла за отведённое время" будет "все
отслеживаемые транзакции уже получили конечный статус, который не изменится". Сравните время до
ошибки с разделом выше (заведомо недостижимые ожидания без этого ускорения) — разница должна быть
заметной (секунды/десятки секунд вместо минут, в зависимости от `pollIntervalSeconds`). Если вместо
`naturalKeys` используется только `minTotalCount` (проверка всей очереди) — это ускорение НЕ должно
срабатывать, шаг обязан честно ждать полный `timeout`, т.к. набор элементов там не закрыт.

### 2c. Fan-in (два независимых шага сводятся в один общий, Sprint 24)

Проверяет обратную ситуацию к 2a — не разветвление, а слияние: `queueA` и `queueB` не связаны между
собой, но оба ведут в один и тот же `job1`. `job1` должен выполниться один раз, только после того как
**оба** `queueA` и `queueB` завершились успешно:

```json
{
  "name": "fan-in-test",
  "steps": [
    { "localId": "queueA", "type": "QUEUE", "name": "Queue A",
      "config": { "name": "sandbox_queue_a", "transactions": [{"naturalKey": "a-1", "value": "v"}] },
      "nextLocalIds": ["job1"] },
    { "localId": "queueB", "type": "QUEUE", "name": "Queue B",
      "config": { "name": "sandbox_queue_b", "transactions": [{"naturalKey": "b-1", "value": "v"}] },
      "nextLocalIds": ["job1"] },
    { "localId": "job1", "type": "JOB", "name": "Job",
      "config": { "rpaProjectId": <RPA_PROJECT_ID_2> }, "nextLocalIds": [] }
  ]
}
```
Ожидаемо: `GET /api/v1/runs/{runId}` показывает `job1` как `RUNNING` только после того, как оба
`queueA`/`queueB` стали `SUCCEEDED` (проверьте по `detail`/логам, что `job1` не стартовал раньше) —
и `job1` выполняется ровно один раз, а не дважды.

Проверка "не запускать, если хотя бы один родитель упал": временно укажите в `queueB.config`
несуществующее `rpaProjectId` или синтетически сломайте один из шагов (например, дублирующееся имя
очереди, которое оркестратор отклонит) — `job1` должен остаться `PENDING` до конца прогона, а не
запуститься по результату одного успешного `queueA`.

Проверка "запуск с места не ждёт несуществующую в этом прогоне ветку": запустите тот же сценарий с
`POST /api/v1/runs/{runId}/start?startStepId=<id очереди queueA>` (см. раздел 11) — `queueB` должен
остаться `PENDING` (он не часть этого прогона), а `job1` должен выполниться сразу после `queueA`,
не дожидаясь `queueB`.

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

Начиная с этого спринта (см. `roadmap.md`, Sprint 13) каждый шаг в ответе несёт не только
`stepId`+`status`, а ещё `stepName`/`stepType` (какой это шаг) и `detail`/`detailUpdatedAt`
(человекочитаемая текущая фаза и когда она последний раз менялась) — например, пока `job1` ждёт
робота:
```json
{
  "stepId": 5,
  "stepName": "First Job",
  "stepType": "JOB",
  "status": "RUNNING",
  "detail": "Задание 'First_Job_24_5' в очереди проектов оркестратора (поставлено ...), ожидание свободного робота (попытка #1)",
  "detailUpdatedAt": "2026-09-04T16:41:02.1+03:00",
  "orchestratorAssignmentId": 123
}
```
а несколько секунд спустя, уже во время выполнения:
```json
{
  "detail": "Задание 'First_Job_24_5' выполняется на роботе 'robot-1' (начато 2026-09-04T16:41:07)",
  "detailUpdatedAt": "2026-09-04T16:41:35.7+03:00"
}
```
Обратите внимание: `detail` называет задание по имени (`'First_Job_24_5'` — сгенерированный
идентификатор `<имя шага>_<runId>_<stepId>`), а не по голому `orchestratorAssignmentId` — тот
по-прежнему есть отдельным полем для программного использования, но в тексте для человека не
фигурирует (см. `roadmap.md`, Sprint 19). Если в `config` задан `rpaProjectId` без
`rpaProjectName`, в сообщениях о создании задания вместо `id=N` тоже покажется имя проекта — оно
резолвится отдельным запросом специально для отображения.

Для `checkInput`/`checkOutput` (`QUEUE_CHECK`) `detail` показывает фактические счётчики на каждой
итерации поллинга (не только на финальном успехе/таймауте), например `"Проверка очереди
'sandbox_input_queue' (попытка #3): всего=1 SUCCESS=1, ожидается SUCCESS=1"`. Если раньше было
неясно, "какой это вообще шаг" и "что сейчас происходит" — сверяйтесь с `stepName`/`stepType`/
`detail` вместо одного лишь `stepId`+`status`. Логи сервера (уровень `com.rpatest: INFO` по
умолчанию) дублируют каждый такой `detail` плюс старт/финиш всего рана и каждого шага — если что-то
неясно из ответа API, тот же текст должен найтись и в логах.

**`steps[]` всегда в порядке выполнения сценария** (по `position` шага, как он задан в
`steps[].localId`/порядку в запросе на создание сценария), а не в порядке, в котором записи вернула
БД — так что `checkInput`/`checkOutput` идут после `job1`, а не вперемешку с `queueIn`/`queueOut`.

**Удалённые транзакции не влияют на `QUEUE_CHECK`.** Если транзакцию удалили из очереди (вручную в
UI оркестратора или самим оркестратором) — `checkInput`/`checkOutput` её не учитывают ни в общем
счётчике (`minTotalCount`), ни в разбивке по статусам (`expectedStatusCounts`), как будто её не
было. Ручной аудит (раздел 4, `GET .../queue-items`) удалённые транзакции по-прежнему показывает —
это отдельный инструмент для отладки, а не для автоматической проверки.

**Вся топология видна сразу, с первого же вызова `GET /api/v1/runs/{runId}` после старта.**
`steps[]` с самого начала содержит **все** шаги сценария, включая те, до которых обход DAG ещё не
дошёл (например, `checkInput`/`checkOutput`, пока `job1` ещё в `RUNNING`) — у них `status: "PENDING"`,
`detail: null`, `startedAt: null`. Если шаг так и остался `PENDING` после того, как весь
`ScenarioRun.status` стал терминальным (`FAILED`/`STOPPED`) — значит обход DAG до него не дошёл
(упал предок, или ран остановили раньше). Если раньше шаг вообще отсутствовал в `steps[]` — это
была ошибка (см. `roadmap.md`, Sprint 15), а не признак того, что его нет в сценарии.

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
   фактическое количество по каждому статусу из `expectedStatusCounts` достигло минимума. Обычно
   это уже мгновенно, так как
   `job1` дожидается реального завершения сам — `checkInput` тут скорее подтверждает бизнес-исход
   (например, что конкретно `tx-1` дошла до `Success`, а не `BusinessError`).
6. `checkOutput` → аналогично поллит `queueOut` на появление транзакций с префиксом `tx-1` →
   `SUCCEEDED`.
7. Весь `ScenarioRun.status` → `SUCCEEDED`.

Если задание зависнет надолго (никто не берёт в работу или робот не отвечает), `job1` БЕЗ
`timeoutSeconds` в `config` будет ждать без ограничения (остановить — `POST /api/v1/runs/{id}/stop`);
с `"timeoutSeconds": 120`, например, упадёт по таймауту через 2 минуты с одним из трёх текстов
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
**С Sprint 23 без явного `naturalKey` этот вызов уже НЕ дампит всю очередь** — сам сужает до того,
что известно `checkOutput`-шагу из его собственного `config`: `naturalKeys`/`naturalKeyPrefixMatch`
(для `QUEUE`-шага — natural key его собственных `transactions`). Ожидаются транзакции, которые
реально записал в `sandbox_output_queue` сам проект по ходу выполнения (не то, что мы отправляли —
мы её только создали пустой). Если пусто — либо проект ещё не успел записать результат (подождите
и повторите), либо имя очереди в `config.name` не совпадает с тем, что реально использует проект.
`checkOutput` (раздел 2b) делает то же самое автоматически и с ожиданием, но этот ручной вызов
удобен для отладки, если `checkOutput` неожиданно падает.

Если шаг сам ничего не отслеживает по ключу (например `QUEUE_CHECK` с одним лишь `minTotalCount`)
— автоматически сузить нечем, вернётся честный постраничный список всей очереди
(`pageNumber`/`pageSize`), как и до Sprint 23. Это ожидаемо, не баг.

Явный `naturalKey` в запросе всегда перекрывает автоматику — полезно посмотреть конкретный ключ,
не совпадающий с тем, что шаг отслеживает сам:
```bash
curl -s "http://localhost:8080/api/v1/runs/$RUN_ID/steps/$QUEUE_OUT_STEP_ID/queue-items?naturalKey=tx-1&naturalKeyPart=true"
```
`naturalKeyPart=true` — поиск по префиксу (полезно для выходной очереди, где к базовому ключу
может дописываться суффикс трассировки); без этого параметра (или `false`) — точное совпадение.

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
`$RUN_ID_2`). Проверьте в оркестраторе: Assignment из этого прогона удалён.

**Важно про очереди:** cleanup удаляет очередь, только если её реально создал именно этот прогон
(`StepRun.orchestratorQueueOwned=true` — видно в `GET /api/v1/runs/$RUN_ID_2`, поле
`orchestratorQueueOwned` у шагов `queueIn`/`queueOut`). В этом сценарии `queueIn`/`queueOut` были
созданы ещё в прогоне из раздела 3 — прогон из раздела 5 их лишь **переиспользовал**
(`orchestratorQueueOwned=false`), поэтому cleanup их **не удалит**, и это ожидаемое поведение:
переиспользованная (не наша) очередь не должна пропадать только потому, что кто-то запустил
cleanup после того, как её использовал. Чтобы увидеть реальное удаление очереди на cleanup — нужен
прогон, который её реально создал (например, самый первый прогон нового сценария с ещё не
существовавшим в оркестраторе именем очереди: сделайте cleanup сразу после него, не запуская
сценарий повторно, и убедитесь, что `orchestratorQueueOwned=true` у `queueIn`/`queueOut` в его
ответе `GET /runs/{runId}`, а после cleanup очередей с этими именами в оркестраторе больше нет).
Прогон из шага 3 при этом уже "устарел" — cleanup всегда работает только с сущностями последнего
прогона, это отдельное, не связанное с владением поведение.

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

## 10. Error-транзакции с настроенными повторами очереди

Если в `checkInput`/`checkOutput` (`QUEUE_CHECK`) заложены ожидания по `ERROR`
(`expectedStatusCounts.ERROR`), а очередь настроена с `maxRetray > 0` (лимит повторных попыток при
ошибке) — проверка теперь ждёт, пока оркестратор реально исчерпает эти попытки, прежде чем считать
`ERROR` финальным результатом. Пока `retray` конкретной транзакции меньше лимита очереди, она
считается `IN_PROGRESS`, а не `ERROR` (см. `roadmap.md`, Sprint 17).

Проверка на реальном стенде: настройте очередь с `maxRetray >= 1`, спровоцируйте ошибку в задании
на конкретной транзакции (например, специально невалидные данные) и понаблюдайте за
`checkInput.detail` во время поллинга (`GET /api/v1/runs/{runId}`) — пока оркестратор ещё
перекладывает транзакцию для повтора, счётчик `ERROR` в `detail` должен быть **меньше**, чем
фактическое число уже произошедших ошибок (лишние уходят в `IN_PROGRESS`), и расти только когда
повторы у конкретной транзакции действительно исчерпаны. Если транзакция в итоге всё равно
завершилась ошибкой после всех повторов — `expectedStatusCounts.ERROR` должен её учесть как обычно.

## 11. Запуск с произвольного шага, а не с начала

Если сценарий уже разок доходил до `job1` (входная очередь уже наполнена, задание не нужно
пересоздавать данные заново) и хочется просто перезапустить/переоценить, например, только
`checkInput`, не гоняя весь сценарий с нуля:

```bash
curl -s -X POST http://localhost:8080/api/v1/scenarios/$SCENARIO_ID/run \
  -H "Content-Type: application/json" \
  -d "{\"startStepId\": $CHECK_INPUT_STEP_ID}"
```

Ожидается: новый `RunResponse` с `startStepId` равным переданному значению; в `GET
/api/v1/runs/{runId}` шаги `queueIn`/`queueOut`/`job1` (всё, что "до" `checkInput` в DAG) остаются
`"status": "PENDING"` навсегда — движок их даже не пытается выполнить, — а `checkInput` (и всё, что
после него, здесь — `checkOutput`) идёт по обычной логике. **Это работает независимо от того,
готовы ли предпосылки на самом деле** — сервис не проверяет, что во входной очереди действительно
есть данные; если пропущенные шаги на самом деле не выполнялись раньше, `checkInput` просто не
найдёт ожидаемых транзакций и упадёт по таймауту, как обычно.

Проверка ошибок:
- `startStepId`, не существующий вообще → `404 NOT_FOUND`.
- `startStepId` шага из **другого** сценария → `400 INVALID_REQUEST`.

## 12. История прогона переживает удаление/редактирование сценария

```bash
curl -s http://localhost:8080/api/v1/runs/$RUN_ID
```
Ответ теперь содержит `"scenarioName"` (имя сценария на момент запуска) рядом с `scenarioId`, а у
каждого шага в `steps[]` — `stepName`/`stepType`, как и раньше, но теперь это значения,
сохранённые в момент запуска, а не «живые» (посмотренные в текущей БД).

Проверка денормализации:
```bash
curl -s -X PUT http://localhost:8080/api/v1/scenarios/$SCENARIO_ID \
  -H "Content-Type: application/json" \
  -d '{"name":"full-flow-test-RENAMED", ...}'   # тот же steps[], только name поменяли
curl -s http://localhost:8080/api/v1/runs/$RUN_ID
```
`scenarioName` в ответе должен остаться прежним (именем на момент запуска), а не смениться на
`"full-flow-test-RENAMED"` — это и есть денормализация, а не подгрузка вживую.

Проверка "прогон переживает удаление сценария":
```bash
curl -s -X DELETE http://localhost:8080/api/v1/scenarios/$SCENARIO_ID -w "\n%{http_code}\n"
curl -s http://localhost:8080/api/v1/runs/$RUN_ID
```
Ожидается: `DELETE` — `204`; последующий `GET /api/v1/runs/$RUN_ID` всё ещё возвращает `200` с
полным телом (`scenarioName`, все `steps[]` со своими `stepName`/`stepType`/статусами/`detail`) —
прогон не пропал вместе со сценарием. (До Sprint 18 такой `GET` вернул бы `404` — сценарий и вся
его история прогонов удалялись каскадно одним запросом.) `POST
/api/v1/scenarios/$SCENARIO_ID/cleanup` и повторный `POST .../scenarios/$SCENARIO_ID/run` для
удалённого сценария по-прежнему должны давать `404` — это ожидаемо, они требуют живого сценария.

## 13. Поиск транзакций по фильтру в большой очереди

Актуально, когда в очереди реально много (тысячи+) транзакций — воспроизвести на реальном стенде,
не на тестовом с парой элементов. Раньше `QUEUE_CHECK`/ручной аудит выгружали очередь целиком
постранично (до 10 000 элементов) и фильтровали на своей стороне — если искомая транзакция лежала
дальше, `checkInput`/`checkOutput` её просто не находили, хотя она реально была в очереди.

1. Наполните входную очередь заметным числом транзакций (сотни-тысячи, зависит от того, что
   реально воспроизводит проблему на вашем стенде), включая одну с известным `naturalKey` среди
   них — в любой позиции, желательно не в начале.
2. Прогоните сценарий с `checkInput.config.naturalKeys: ["<этот naturalKey>"]`.
3. Ожидается: шаг находит транзакцию и проходит `SUCCEEDED` независимо от того, где именно она
   лежит в очереди — не зависает и не падает по таймауту с "фактически=0" для существующей
   транзакции.
4. Параллельно проверьте ручной аудит с фильтром (раздел 4) на том же `naturalKey` — должен сразу
   найти нужную транзакцию, а не только первые несколько сотен элементов очереди.

Если результат воспроизводит старую проблему (транзакция не находится) — вероятная причина:
`NaturalKey`/`NaturalKeyPart` на реальном стенде игнорируются оркестратором молча (см.
предупреждение в `roadmap.md`, "Открытые риски") — эти параметры не задокументированы в
`OrcService.java`/`orc_worker.py`, только в схеме, и не проверялись против реального оркестратора
в этой сессии.

## 14. Блок запуска при нехватке свободных роботов

```bash
curl -s -X POST http://localhost:8080/api/v1/scenarios/$SCENARIO_ID/run -w "\n%{http_code}\n"
```

Чтобы воспроизвести блокировку, заранее займите роботов оркестратора (запустите на них вручную
любые проекты через UI/API оркестратора, либо просто дождитесь момента, когда на стенде и так
занято/недоступно больше `orchestrator.min-free-robots - 1` роботов) так, чтобы свободных осталось
меньше `orchestrator.min-free-robots` (по умолчанию 2).

Ожидается:
- Если свободных роботов меньше порога — `409`, тело `{"code":"CONFLICT","message":"Недостаточно
  свободных роботов на оркестраторе для запуска: свободно N из M, требуется минимум K. Попробуйте
  запустить сценарий позже.", ...}`. **`ScenarioRun` при этом не создаётся вообще** — повторный
  `GET /api/v1/scenarios/$SCENARIO_ID` не должен показывать никакого нового прогона, а
  `POST .../cleanup` — по-прежнему ссылаться на предыдущий (если был), не на этот несостоявшийся.
- Если свободных роботов хватает — обычный `202`, дальше как в разделе 3.
- Освободите роботов (остановите занимавшие их проекты) и повторите — запуск должен пройти.

Проверка настраиваемости порога: временно переопределите `ORCHESTRATOR_MIN_FREE_ROBOTS` (например,
в `1`) и перезапустите сервис — тот же стенд с прежним числом свободных роботов, который до этого
блокировал запуск, теперь должен пропускать его.

Проверка независимости от топологии сценария (осознанное упрощение, см. `roadmap.md`): даже
сценарий без единого `JOB`-шага (только `QUEUE`/`QUEUE_CHECK`) при нехватке свободных роботов
всё равно получит `409` — это ожидаемое, а не ошибочное поведение на данный момент.

## 15. Поллинг доступности роботов (для фронта)

```bash
curl -s http://localhost:8080/api/v1/orchestrator/robots-availability
```
Ожидается, независимо от того, есть ли вообще какой-либо сценарий/прогон:
```json
{"freeRobots": 1, "totalRobots": 3, "minFreeRobots": 2, "launchAllowed": false}
```

Проверка согласованности с реальным запуском (это и есть весь смысл эндпоинта — фронт должен
доверять `launchAllowed`, не проверяя отдельно): при `launchAllowed: false` в этом ответе —
`POST /api/v1/scenarios/$SCENARIO_ID/run`, выполненный сразу следом (без изменений на стенде
между вызовами), должен ответить `409` (раздел 14). При `launchAllowed: true` — тот же `POST`
должен пройти (`202`), если, конечно, доступность роботов не изменилась в промежутке между двумя
запросами (сам поллинг ничего не резервирует и не блокирует — это просто снимок на момент опроса).

Эндпоинт read-only и не создаёт побочных эффектов — опросите его несколько раз подряд и убедитесь,
что состояние стенда (роботы, очереди, сценарии) не меняется.

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
- [ ] Cleanup удаляет Assignment последнего прогона всегда; очередь — только если её реально
      создал именно этот прогон (`orchestratorQueueOwned=true`), переиспользованную очередь не
      трогает — раздел 6
- [ ] Повторный запуск того же сценария (с ожиданием завершения предыдущего) проходит без
      конфликтов по имени Assignment; очереди переиспользуются, а не падают на конфликте имени —
      см. раздел 9
- [ ] Ошибка оркестратора (несуществующий проект и т.п.) даёт `FAILED` с понятным сообщением, а
      не падение сервиса
- [ ] Запуск **второго** Assignment того же `rpaProjectId`, пока первый ещё не завершён, даёт
      понятную ошибку (`501`, "запрещены повторы в очереди ожидания") — а не тихо виснет
- [ ] `QUEUE_CHECK` (раздел 2b) с достижимыми ожиданиями проходит `SUCCEEDED`, с заведомо
      недостижимыми — падает по таймауту с понятным "ожидалось/фактически" в `errorMessage`;
      фактическое количество **больше** заданного в `expectedStatusCounts` тоже проходит проверку
      (минимум, не точное совпадение, см. Sprint 22)
- [ ] `job1.config.rpaProjectName` с реальным именем проекта резолвится в тот же `rpaProjectId`,
      что и явное указание id; с несуществующим именем — падает сразу с понятным сообщением, не
      создавая Assignment
- [ ] `ERROR`-транзакция с неисчерпанными повторами очереди (`retray < maxRetray`) не считается
      финальной ошибкой в `QUEUE_CHECK` — раздел 10
- [ ] Запуск с `startStepId` пропускает шаги "до" него (остаются `PENDING`) и стартует прямо с
      указанного шага; `startStepId` из другого сценария/несуществующий — `400`/`404` — раздел 11
- [ ] `RunResponse.scenarioName` и `StepRunResponse.stepName`/`stepType` — денормализованные
      значения на момент запуска (не меняются, если сценарий/шаг потом переименовали); `GET
      /api/v1/runs/{runId}` продолжает работать (`200`, с полным телом) даже после удаления
      сценария, к которому этот прогон относился — раздел 12
- [ ] `QUEUE_CHECK`/ручной аудит находят транзакцию по `naturalKeys`/`naturalKey` независимо от
      того, сколько всего элементов в очереди и в какой они позиции (не только среди первых ~10000
      постранично просматриваемых) — раздел 13
- [ ] `detail` шага `JOB` и сообщения о таймауте/ошибке называют задание и проект по имени
      (`'First_Job_24_5'`, имя проекта), а не голым `id=N` — раздел 3
- [ ] Запуск сценария при менее чем `orchestrator.min-free-robots` (по умолчанию 2) свободных
      роботах даёт `409` и не создаёт `ScenarioRun`; при достаточном числе — обычный `202` — раздел 14
- [ ] `GET /api/v1/orchestrator/robots-availability` даёт `launchAllowed`, согласованный с тем,
      что реально ответит `POST .../run` в тот же момент — раздел 15
- [ ] `GET .../queue-items` без явного `naturalKey` показывает не всю очередь, а только ключи,
      известные самому шагу (`transactions` для `QUEUE`, `naturalKeys` для `QUEUE_CHECK`) —
      раздел 4
- [ ] Fan-in (раздел 2c): узел с двумя независимыми родителями выполняется один раз, только после
      успеха обоих; при падении одного родителя остаётся `PENDING`; при запуске с `startStepId`
      родитель вне этого прогона не блокирует узел ожиданием
- [ ] Без токена (или с истёкшим/невалидным) — `401` на любом эндпоинте, кроме `/actuator/health` и
      `/api/v1/auth/*` — раздел 0
- [ ] Матрица ролей соблюдается: `VIEWER` — только чтение (`403` на запись); `OPERATOR` — чтение и
      операционные действия, но `403` на `DELETE /scenarios/{id}` и на `/api/v1/admin/**`; `ADMIN` —
      всё — раздел 0b
- [ ] `/api/v1/admin/users` доступен только `ADMIN` (`403` для остальных ролей); дубликат
      `username` — `409`; короткий пароль — `400` — раздел 0c
- [ ] Деактивация пользователя (`enabled:false`) блокирует и логин, и уже выданный refresh-токен
      (не только будущий логин) — раздел 0c
- [ ] Refresh-токен одноразовый (ротация): повторное использование того же токена после успешного
      `/auth/refresh` — `401`; `logout` тоже делает токен непригодным — раздел 0d
- [ ] `ScenarioRun.triggeredBy` в ответе `GET /api/v1/runs/{runId}` — имя реально залогиненного
      пользователя, а не то, что можно было бы подставить в теле запроса (в `RunRequest` такого поля
      больше нет)
- [ ] `/auth/login`/`/auth/refresh` не возвращают `accessToken`/`refreshToken` в JSON-теле (только
      `expiresInSeconds`) — токены только в `Set-Cookie`, `HttpOnly`+`Secure`+`SameSite=Strict` —
      раздел 0a
- [ ] `/auth/refresh`/`/auth/logout` без куки с refresh-токеном (не было логина, либо кука не
      приложена) — `401`/`INVALID_CREDENTIALS`, не 500 и не "тихий" успех — раздел 0a/0d
- [ ] Досрочный выход `QUEUE_CHECK` при непустом `naturalKeys`, если все отслеживаемые транзакции
      уже в конечном статусе — падает заметно раньше `timeout`, не дожидаясь его полностью; без
      `naturalKeys` (только `minTotalCount`) — честно ждёт `timeout`, как раньше — раздел 2b
- [ ] `GET /api/v1/auth/me` возвращает `username`/`role` текущего пользователя, доступен любой
      аутентифицированной роли (не только `ADMIN`/`OPERATOR`); без куки/с протухшей — `401` — раздел 0a
- [ ] Смена своего пароля (раздел 0e): неверный текущий — `400`, одинаковый новый — `400`, после
      смены прочие сессии разлогинены, текущая жива
- [ ] Матрица прав редактируется админом без перелогина и без деплоя; `ADMIN` не редактируется;
      неизвестное право/роль — `400` — раздел 0f
- [ ] Шаги `JOB`/`QUEUE_CHECK` без `timeoutSeconds` ждут без ограничения, с `timeoutSeconds` падают
      по нему; глобальных таймаутов ожидания нет (ADR 0006) — разделы 2b, 3
