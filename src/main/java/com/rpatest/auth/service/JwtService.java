package com.rpatest.auth.service;

import com.rpatest.auth.domain.AppUser;
import com.rpatest.auth.domain.Role;
import com.rpatest.config.AuthProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Component;

/**
 * Выпускает и проверяет access-токен (HS256, см. ADR 0003) — в отличие от {@code
 * orchestrator.auth.JwtExpiryReader}, который только читает {@code exp} из ЧУЖОГО токена без
 * проверки подписи (валидность подтверждает сам оркестратор), этот сервис — единственный источник
 * истины для токенов НАШЕГО API и обязан проверять подпись.
 */
@Component
public class JwtService {

    private static final String ROLE_CLAIM = "role";

    private final AuthProperties.Jwt properties;
    private SecretKey signingKey;

    public JwtService(AuthProperties properties) {
        this.properties = properties.getJwt();
    }

    @PostConstruct
    void init() {
        String secret = properties.getSecret();
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException(
                    "auth.jwt.secret не задан или короче 32 байт (256 бит) — обязателен для HS256");
        }
        this.signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public String issueAccessToken(AppUser user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(user.getUsername())
                .claim(ROLE_CLAIM, user.getRole().name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(properties.getAccessTokenTtl())))
                .signWith(signingKey)
                .compact();
    }

    public Duration accessTokenTtl() {
        return properties.getAccessTokenTtl();
    }

    /** {@code null}, если токен отсутствует/просрочен/подделан — не бросает наружу detали jjwt. */
    public AccessTokenClaims parse(String token) {
        try {
            Claims claims = Jwts.parser().verifyWith(signingKey).build()
                    .parseSignedClaims(token).getPayload();
            return new AccessTokenClaims(claims.getSubject(), Role.valueOf(claims.get(ROLE_CLAIM, String.class)));
        } catch (JwtException | IllegalArgumentException e) {
            return null;
        }
    }

    public record AccessTokenClaims(String username, Role role) {
    }
}
