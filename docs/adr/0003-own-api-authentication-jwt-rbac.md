# 0003 — Собственная аутентификация API: JWT + 3 роли, без self-registration

- Status: Accepted
- Date: 2026-09-29

## Context

Весь свой API (`ScenarioController`, `RunController`, `OrchestratorController`,
`CleanupController` — 11 эндпоинтов) был полностью открыт: аутентификация существовала только к
оркестратору (`TokenProvider`/Jasypt-креды), не к нашему собственному сервису. Обсуждались и
взвешивались:

- **Механизм**: JWT (stateless) / серверная сессия (cookie, `spring-session`) / статические API-ключи.
- **Роли**: сколько уровней доступа, одна роль на пользователя или M:N.
- **Провижининг**: self-registration или только через админа.
- **Область защиты**: сразу все эндпоинты или поэтапно.

## Decision

**JWT stateless**, не сессия и не API-ключи — сервис уже REST/stateless (нет sticky-session
инфраструктуры), и это единственный вариант, естественно подходящий фронту-SPA (поллинг
доступности роботов, блокировка кнопок — см. Sprint 20-21). Access-токен (HS256, 15 минут) +
refresh-токен (7 дней). Access — короткоживущий подписанный JWT, не хранится в БД (валидность
проверяется по подписи, `JwtService`). Refresh — НЕ JWT, а непрозрачный случайный токен: в БД
хранится только его SHA-256 хэш (`RefreshToken`), с ротацией на каждое обновление. Причина:
stateless refresh-JWT нельзя отозвать до истечения его собственного срока — при отключении
пользователя (`AppUserService.setEnabled(false)`) это означало бы, что отключение блокирует только
будущий логин, а уже выданный refresh продолжал бы работать до 7 дней. БД-хранимый токен отзывается
немедленно (`RefreshTokenService.revokeAllForUser`).

**Роли — `ADMIN`/`OPERATOR`/`VIEWER`, одна роль на пользователя** (enum-колонка на `AppUser`, не
M:N-таблица) — трёх уровней достаточно (управление пользователями / операционные действия /
только чтение), а если пользователю понадобится больше одной роли — это отдельное решение, которое
нужно принимать осознанно, а не готовить заранее без реального сценария использования. Матрица
доступа — единый список в `SecurityConfig.securityFilterChain` (`authorizeHttpRequests`), НЕ
`@PreAuthorize` по контроллерам: так политику видно и проверяемо целиком в одном месте, вместо того
чтобы собирать её по всем контроллерам вручную (см. `codebase-design`: один seam для всей матрицы,
не размазанный по модулям).

**Только admin-провижининг**, self-registration нет — `AdminUserController` (`/api/v1/admin/users`,
`ADMIN`-only). Первый `ADMIN` заводится `AdminBootstrapRunner` (`CommandLineRunner`, идемпотентно —
срабатывает только когда `app_user` пуста) из `auth.bootstrap-admin.*`, не Flyway-миграцией — чтобы
реальный пароль не становился содержимым репозитория ни в каком виде.

**Деактивация пользователя — мягкая (`enabled=false`), не удаление.** Удаление осиротило бы
`ScenarioRun.triggeredBy` так же, как `agents.md` запрещает для истории `scenario`/`step`
(`ON DELETE SET NULL`, не `CASCADE`) — тот же принцип применён здесь: строка `app_user` остаётся
для аудита, отключение блокирует логин и отзывает refresh-токены.

**Защита включена сразу на все 11 эндпоинтов**, не поэтапно — наполовину защищённый API хуже
явно незащищённого: легко забыть вторую половину. `GET /api/v1/orchestrator/robots-availability`
тоже под ролью (`VIEWER`+), несмотря на то что раньше рассматривался как "пред-логин" индикатор —
решили, что фронт логинится первым, отдельного пред-логин-статуса не нужно. Актуаторные
`/actuator/health`/`/actuator/info` — единственное исключение (`permitAll`), это операционная
конвенция для health-check инфраструктуры (балансировщик не может аутентифицироваться), а не часть
нашего функционального API.

**`ScenarioRun.triggeredBy` теперь берётся из `Authentication`, не из тела запроса.** До этого
решения `RunRequest.triggeredBy` был произвольной строкой от клиента — бессмысленное поле для
аудита при наличии реальной идентичности. Колонка осталась (денормализованная строка, тот же
паттерн, что `scenarioName`/`stepName` — см. `agents.md`), просто источник значения сменился.

## Consequences

- Новая зависимость: `spring-boot-starter-security`, `io.jsonwebtoken:jjwt-*` (0.12.x).
- Новый пакет `com.rpatest.auth` (`domain`/`repository`/`service`/`web`/`config`) — единственное
  место, владеющее аутентификацией/пользователями; `SecurityConfig`/`AuthProperties` — в
  `com.rpatest.config`, рядом с `OrchestratorProperties`, тот же паттерн `@ConfigurationProperties`.
- Существующие `@WebMvcTest`-слайсы контроллеров получили `@AutoConfigureMockMvc(addFilters = false)`
  — они тестируют HTTP-маппинг, не матрицу доступа; матрица доступа проверяется отдельно, одним
  файлом (`SecurityConfigAuthorizationTest`, реальный `SecurityConfig`, `@WithMockUser` с разными
  ролями) — тот же принцип "один seam — одно место, где это тестируется".
- `JwtAuthenticationFilter` (как `@Component`, реализующий `Filter`) автоматически подтягивается в
  любой `@WebMvcTest`-слайс независимо от `addFilters=false` (сам бин создаётся, просто не
  регистрируется в MockMvc) — каждый такой слайс-тест обязан мокать `JwtService`, иначе контекст не
  поднимется (`UnsatisfiedDependencyException`). Учитывайте это при добавлении новых
  `@WebMvcTest`-тестов.
- Новые миграции `V10__app_user.sql`, `V11__refresh_token.sql`, покрытые `AppUserRepositoryIT`/
  `RefreshTokenRepositoryIT` (Testcontainers).
