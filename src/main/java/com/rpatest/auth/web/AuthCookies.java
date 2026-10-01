package com.rpatest.auth.web;

import com.rpatest.config.AuthProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * Единственное место, где токены превращаются в `Set-Cookie`/читаются обратно (ADR 0004) — не
 * JSON-тело ответа/запроса, чтобы токены были недоступны JS на фронте (`HttpOnly`) и не лежали в
 * `localStorage`, откуда их видно и можно подменить. `access_token` — `Path=/` (нужен на каждый
 * защищённый запрос), `refresh_token` — `Path=/api/v1/auth` (нужен только `/refresh`/`/logout`,
 * сужаем, чтобы не утекал с каждым обычным API-запросом).
 */
@Component
public class AuthCookies {

    public static final String ACCESS_TOKEN_COOKIE = "access_token";
    public static final String REFRESH_TOKEN_COOKIE = "refresh_token";
    private static final String AUTH_PATH = "/api/v1/auth";

    private final AuthProperties.Jwt jwtProperties;
    private final boolean secure;

    public AuthCookies(AuthProperties properties) {
        this.jwtProperties = properties.getJwt();
        this.secure = properties.getCookies().isSecure();
    }

    public ResponseCookie accessTokenCookie(String rawToken) {
        return build(ACCESS_TOKEN_COOKIE, rawToken, "/", jwtProperties.getAccessTokenTtl());
    }

    public ResponseCookie refreshTokenCookie(String rawToken) {
        return build(REFRESH_TOKEN_COOKIE, rawToken, AUTH_PATH, jwtProperties.getRefreshTokenTtl());
    }

    public ResponseCookie clearAccessTokenCookie() {
        return build(ACCESS_TOKEN_COOKIE, "", "/", Duration.ZERO);
    }

    public ResponseCookie clearRefreshTokenCookie() {
        return build(REFRESH_TOKEN_COOKIE, "", AUTH_PATH, Duration.ZERO);
    }

    public Optional<String> readAccessToken(HttpServletRequest request) {
        return readCookie(request, ACCESS_TOKEN_COOKIE);
    }

    public Optional<String> readRefreshToken(HttpServletRequest request) {
        return readCookie(request, REFRESH_TOKEN_COOKIE);
    }

    private Optional<String> readCookie(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        return Arrays.stream(cookies).filter(c -> c.getName().equals(name)).map(Cookie::getValue).findFirst();
    }

    private ResponseCookie build(String name, String value, String path, Duration maxAge) {
        return ResponseCookie.from(name, value)
                .httpOnly(true)
                .secure(secure)
                .sameSite("Strict")
                .path(path)
                .maxAge(maxAge)
                .build();
    }
}
