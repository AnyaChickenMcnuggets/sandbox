package com.rpatest.auth.web;

import com.rpatest.auth.service.JwtService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Достаёт access-токен — сначала из {@code HttpOnly}-куки {@code access_token} (основной путь для
 * браузерного фронта, см. {@link AuthCookies}, ADR 0004), иначе из {@code Authorization: Bearer}
 * (для curl/скриптов/тестов — см. `TESTING.md`). Ничего не решает про 401/403 сама — если токен
 * отсутствует/невалиден, просто не аутентифицирует запрос, а решение "публичный путь или нет"
 * остаётся за матрицей в {@code SecurityConfig}.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final AuthCookies authCookies;

    public JwtAuthenticationFilter(JwtService jwtService, AuthCookies authCookies) {
        this.jwtService = jwtService;
        this.authCookies = authCookies;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        extractToken(request).map(jwtService::parse).ifPresent(claims -> {
            List<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("ROLE_" + claims.role().name()));
            var authentication = new UsernamePasswordAuthenticationToken(claims.username(), null, authorities);
            SecurityContextHolder.getContext().setAuthentication(authentication);
        });
        filterChain.doFilter(request, response);
    }

    private Optional<String> extractToken(HttpServletRequest request) {
        Optional<String> fromCookie = authCookies.readAccessToken(request);
        if (fromCookie.isPresent()) {
            return fromCookie;
        }
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            return Optional.of(header.substring(BEARER_PREFIX.length()));
        }
        return Optional.empty();
    }
}
