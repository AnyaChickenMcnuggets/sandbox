package com.rpatest.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rpatest.auth.domain.Permission;
import com.rpatest.auth.service.PermissionAuthorization;
import com.rpatest.auth.web.JwtAuthenticationFilter;
import com.rpatest.common.web.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Единственное место, где эндпоинты привязаны к правам (см. ADR 0003, 0005) — через {@code
 * authorizeHttpRequests}, а не {@code @PreAuthorize} по контроллерам, чтобы привязку можно было
 * целиком увидеть/проверить в одном файле. Какие права есть у какой роли — не здесь, а в БД
 * ({@code RolePermissionService}), редактируется админом через {@code /api/v1/admin/roles}.
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(AuthProperties.class)
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final ObjectMapper objectMapper;
    private final PermissionAuthorization permissions;

    public SecurityConfig(
            JwtAuthenticationFilter jwtAuthenticationFilter, ObjectMapper objectMapper, PermissionAuthorization permissions) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.objectMapper = objectMapper;
        this.permissions = permissions;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // CSRF отключён намеренно (ADR 0004), не по умолчанию "забыли": токены лежат в
                // куках с SameSite=Strict — браузер физически не приложит их к запросу с чужого
                // origin (ни через form-submit, ни через fetch/XHR), классический CSRF-вектор для
                // такого cookie не существует. Авторизационный заголовок (curl/скрипты, см.
                // JwtAuthenticationFilter) тоже не уязвим — его не поставить без явного JS на том
                // же origin, что уже не CSRF, а XSS. Если когда-нибудь появится необходимость
                // отправлять куки cross-site (SameSite=None) — ОБЯЗАТЕЛЬНО включить CSRF обратно.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(eh -> eh
                        .authenticationEntryPoint(this::writeUnauthorized)
                        .accessDeniedHandler((request, response, ex) -> writeForbidden(response)))
                // Порядок важен: первое совпавшее правило побеждает. Здесь только привязка
                // эндпоинт -> право (Permission); кому какое право выдано — матрица ролей в БД
                // (RolePermissionService, ADR 0005), её редактирует админ без деплоя.
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/login", "/api/v1/auth/refresh",
                                "/api/v1/auth/logout").permitAll()
                        .requestMatchers("/api/v1/admin/users/**").access(permissions.has(Permission.USER_MANAGE))
                        .requestMatchers("/api/v1/admin/roles/**", "/api/v1/admin/permissions")
                        .access(permissions.has(Permission.ROLE_MANAGE))
                        .requestMatchers("/api/v1/admin/**").denyAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/scenarios/*/run")
                        .access(permissions.has(Permission.RUN_START))
                        .requestMatchers(HttpMethod.POST, "/api/v1/scenarios/*/cleanup")
                        .access(permissions.has(Permission.CLEANUP))
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/scenarios/*")
                        .access(permissions.has(Permission.SCENARIO_DELETE))
                        .requestMatchers(HttpMethod.GET, "/api/v1/scenarios", "/api/v1/scenarios/*")
                        .access(permissions.has(Permission.SCENARIO_READ))
                        .requestMatchers(HttpMethod.POST, "/api/v1/scenarios")
                        .access(permissions.has(Permission.SCENARIO_WRITE))
                        .requestMatchers(HttpMethod.PUT, "/api/v1/scenarios/*")
                        .access(permissions.has(Permission.SCENARIO_WRITE))
                        .requestMatchers(HttpMethod.POST, "/api/v1/runs/*/stop")
                        .access(permissions.has(Permission.RUN_STOP))
                        .requestMatchers(HttpMethod.GET, "/api/v1/runs/**").access(permissions.has(Permission.RUN_READ))
                        .requestMatchers(HttpMethod.GET, "/api/v1/orchestrator/**")
                        .access(permissions.has(Permission.ORCHESTRATOR_READ))
                        // Всё остальное под нашими префиксами (другой метод/путь, которому не
                        // сопоставлено право) — закрыто, а не "просто аутентифицирован": новый
                        // эндпоинт без явного права не должен случайно оказаться открытым
                        .requestMatchers("/api/v1/scenarios/**", "/api/v1/runs/**", "/api/v1/orchestrator/**")
                        .denyAll()
                        // /api/v1/auth/me, /api/v1/auth/change-password — любой аутентифицированный
                        .anyRequest().authenticated())
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    private void writeUnauthorized(HttpServletRequest request, HttpServletResponse response, AuthenticationException ex)
            throws IOException {
        writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "UNAUTHORIZED", "Требуется аутентификация");
    }

    private void writeForbidden(HttpServletResponse response) throws IOException {
        writeError(response, HttpServletResponse.SC_FORBIDDEN, "FORBIDDEN", "Недостаточно прав для этого действия");
    }

    private void writeError(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(objectMapper.writeValueAsString(ErrorResponse.of(code, message)));
    }
}
