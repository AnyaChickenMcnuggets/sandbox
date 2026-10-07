package com.rpatest.auth.service;

import com.rpatest.auth.domain.RefreshToken;
import com.rpatest.auth.repository.RefreshTokenRepository;
import com.rpatest.config.AuthProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Хранит только SHA-256 хэш refresh-токена (см. {@code RefreshToken}), не сам токен. Ротация при
 * каждом обновлении (старый помечается revoked, выдаётся новый) — предотвращает повторное
 * использование украденного refresh-токена после легитимного обновления.
 */
@Component
public class RefreshTokenService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final RefreshTokenRepository repository;
    private final AuthProperties.Jwt properties;

    public RefreshTokenService(RefreshTokenRepository repository, AuthProperties properties) {
        this.repository = repository;
        this.properties = properties.getJwt();
    }

    @Transactional
    public String issue(Long appUserId) {
        String rawToken = generateRawToken();
        OffsetDateTime expiresAt = OffsetDateTime.now().plus(properties.getRefreshTokenTtl());
        repository.save(new RefreshToken(appUserId, hash(rawToken), expiresAt));
        return rawToken;
    }

    /** Находит владельца действующего (не отозванного, не просроченного) токена и сразу отзывает
     * его — вызывающая сторона обязана выдать новый через {@link #issue}, если хочет продолжения
     * сессии (ротация), а не молча продлевать один и тот же refresh-токен бессрочно. */
    @Transactional
    public Optional<Long> consumeIfUsable(String rawToken) {
        return repository.findByTokenHash(hash(rawToken))
                .filter(RefreshToken::isUsable)
                .map(token -> {
                    token.revoke();
                    return token.getAppUserId();
                });
    }

    @Transactional
    public void revoke(String rawToken) {
        repository.findByTokenHash(hash(rawToken)).ifPresent(RefreshToken::revoke);
    }

    /** Обесценивает все действующие refresh-токены пользователя — вызывается при деактивации
     * учётной записи ({@code AppUserService.setEnabled(false)}), чтобы отключение реально мешало
     * дальше пользоваться API, а не только блокировало будущий логин. */
    @Transactional
    public void revokeAllForUser(Long appUserId) {
        List<RefreshToken> active = repository.findByAppUserIdAndRevokedAtIsNull(appUserId);
        active.forEach(RefreshToken::revoke);
        repository.saveAll(active);
    }

    private String generateRawToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hash(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hashed);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available in this JVM", e);
        }
    }
}
