# Инструкция: включить HTTPS

С Sprint 27 токены аутентификации лежат в `Secure`-куках (см. ADR 0004,
`docs/adr/0004-cookie-based-tokens-and-https.md`) — браузер физически не отправит такую куку назад
по обычному `http://`, только по `https://`. Без HTTPS логин технически пройдёт (кука придёт в
`Set-Cookie`), но браузер её молча отбросит, и следующий запрос окажется неаутентифицированным.
Поэтому прод/стейджинг теперь **обязаны** быть на HTTPS — это не опционально.

Два варианта. Выберите один.

## Вариант А (рекомендуется): TLS на реверс-прокси

Spring Boot остаётся на обычном HTTP внутри периметра, прокси перед ним терминирует TLS. Проще в
сопровождении (ротация сертификата — забота прокси/инфраструктуры, не приложения), и чаще всего уже
есть готовая корпоративная практика для этого (тот же паттерн, что наверняка используется для других
внутренних сервисов на работе — спросите DevOps/инфраструктурную команду, возможно, уже есть шаблон).

Пример для nginx:
```nginx
server {
    listen 443 ssl;
    server_name rpa-test.internal.example.com;

    ssl_certificate     /etc/ssl/rpa-test/fullchain.pem;
    ssl_certificate_key /etc/ssl/rpa-test/privkey.pem;

    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-Proto https;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    }
}

server {
    listen 80;
    server_name rpa-test.internal.example.com;
    return 301 https://$host$request_uri;
}
```
Приложение уже настроено доверять этим заголовкам (`server.forward-headers-strategy: framework` в
`application.yml`) — ничего дополнительно в коде менять не нужно.

Сертификат — свой корпоративный CA (если внутренний домен) или Let's Encrypt (если домен публично
резолвится). Если сертификат выпущен внутренним CA — убедитесь, что браузеры в сети ему доверяют
(обычно уже настроено через групповые политики), иначе у пользователей будет предупреждение
"небезопасное соединение", хоть сама кука и будет исправно работать.

**Если фронтенд — отдельное SPA-приложение**: разместите его за тем же прокси на том же origin
(например, фронт на `/`, API на `/api/**` — тот же `server_name`/порт/протокол), не на отдельном
домене/порте. Куки с `SameSite=Strict` не уйдут на другой origin даже по HTTPS — same-origin здесь
не формальность, а обязательное условие работы кук (см. раздел "Если фронт на другом origin" ниже).

## Вариант Б: TLS прямо в приложении

Если реверс-прокси нет и его не поставить — Spring Boot сам терминирует TLS.

1. Сгенерировать keystore (PKCS12) со своим сертификатом — если сертификат уже выпущен
   (корпоративный CA/Let's Encrypt), импортируйте его:
   ```bash
   openssl pkcs12 -export -in fullchain.pem -inkey privkey.key \
     -out keystore.p12 -name rpa-test-automation -password pass:<KEYSTORE_PASSWORD>
   ```
   Самоподписанный — только для теста варианта, не для прода:
   ```bash
   keytool -genkeypair -alias rpa-test-automation -keyalg RSA -keysize 2048 \
     -storetype PKCS12 -keystore keystore.p12 -validity 365 \
     -dname "CN=rpa-test.internal.example.com" -storepass <KEYSTORE_PASSWORD>
   ```
2. Положить `keystore.p12` туда, откуда сервис его прочитает (НЕ в `src/main/resources` — не
   должен попасть в репозиторий/jar с реальным сертификатом внутри).
3. Раскомментировать блок `server.ssl.*` в `application.yml` (уже подготовлен) и задать переменные
   окружения:
   ```bash
   export SERVER_SSL_KEYSTORE=file:/etc/rpa-test/keystore.p12
   export SERVER_SSL_KEYSTORE_PASSWORD=<KEYSTORE_PASSWORD>
   ```
4. Перезапустить — сервис теперь слушает `https://host:8080` напрямую (`server.port` тот же).
   Обычный `http://` на этом порту перестанет отвечать (это ожидаемо).

Минус варианта: ротация сертификата — ручной редеплой/перезапуск с новым keystore, а не
прозрачная замена файла на прокси.

## Локальная разработка без HTTPS

Для `localhost` можно не поднимать TLS вообще — задайте:
```bash
export AUTH_COOKIES_SECURE=false
```
Кука перестаёт требовать HTTPS (`Secure` снимается). **Никогда не ставьте `false` в
проде/стейджинге** — без `Secure` кука уходит и по обычному http, token можно перехватить
MITM-ом на открытой сети. Если хочется тестировать именно HTTPS-путь локально — `mkcert`
(`https://github.com/FiloSottile/mkcert`) выпускает локально доверенный сертификат для
`localhost` за одну команду, тогда `AUTH_COOKIES_SECURE` можно оставить `true`.

## Если фронтенд на другом origin (другой домен/порт)

`SameSite=Strict` куки не уходят на cross-site запросы вообще — ни к другому порту, ни к другому
поддомену. Если фронт и бэкенд по архитектуре должны жить на разных origin (например, SPA на CDN,
API — отдельно), куки-подход так не заработает без дополнительных мер (`SameSite=None` + `Secure` +
CORS с `credentials`), а это новый разговор с отдельными компромиссами по CSRF — скажите, обсудим
отдельно, не меняйте `SameSite` в одиночку: это прямо противоречит ADR 0004.
