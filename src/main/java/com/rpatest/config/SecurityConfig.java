package com.rpatest.config;

import com.fasterxml.jackson.databind.ObjectMapper;
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
 * Единственное место, где живёт вся матрица доступа (см. ADR 0003) — роли размечены здесь через
 * {@code authorizeHttpRequests}, а не {@code @PreAuthorize} по контроллерам, чтобы политику можно
 * было целиком увидеть/проверить в одном файле, а не собирать по 4 контроллерам вручную.
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(AuthProperties.class)
public class SecurityConfig {

    private static final String[] VIEWER_UP = {"ADMIN", "OPERATOR", "VIEWER"};
    private static final String[] OPERATOR_UP = {"ADMIN", "OPERATOR"};

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final ObjectMapper objectMapper;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter, ObjectMapper objectMapper) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.objectMapper = objectMapper;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(eh -> eh
                        .authenticationEntryPoint(this::writeUnauthorized)
                        .accessDeniedHandler((request, response, ex) -> writeForbidden(response)))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/**").permitAll()
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/scenarios/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/v1/scenarios/**", "/api/v1/runs/**", "/api/v1/orchestrator/**")
                        .hasAnyRole(VIEWER_UP)
                        .requestMatchers("/api/v1/scenarios/**", "/api/v1/runs/**").hasAnyRole(OPERATOR_UP)
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
