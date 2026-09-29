package com.rpatest.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rpatest.auth.domain.RefreshToken;
import com.rpatest.auth.repository.RefreshTokenRepository;
import com.rpatest.config.AuthProperties;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RefreshTokenServiceTest {

    private RefreshTokenRepository repository;
    private RefreshTokenService service;

    @BeforeEach
    void setUp() {
        repository = mock(RefreshTokenRepository.class);
        AuthProperties properties = new AuthProperties();
        properties.getJwt().setRefreshTokenTtl(Duration.ofDays(7));
        service = new RefreshTokenService(repository, properties);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void issueSavesHashNotRawToken() {
        String rawToken = service.issue(42L);

        org.mockito.ArgumentCaptor<RefreshToken> captor = org.mockito.ArgumentCaptor.forClass(RefreshToken.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getAppUserId()).isEqualTo(42L);
        assertThat(captor.getValue().getTokenHash()).isNotEqualTo(rawToken);
    }

    @Test
    void consumeIfUsableFindsAndRevokesUsableToken() {
        RefreshToken stored = new RefreshToken(42L, anyHash(), OffsetDateTime.now().plusDays(1));
        when(repository.findByTokenHash(anyString())).thenReturn(Optional.of(stored));

        Optional<Long> result = service.consumeIfUsable("raw-token");

        assertThat(result).contains(42L);
        assertThat(stored.getRevokedAt()).isNotNull();
    }

    @Test
    void consumeIfUsableReturnsEmptyForUnknownToken() {
        when(repository.findByTokenHash(anyString())).thenReturn(Optional.empty());

        assertThat(service.consumeIfUsable("raw-token")).isEmpty();
    }

    @Test
    void consumeIfUsableReturnsEmptyForExpiredToken() {
        RefreshToken expired = new RefreshToken(42L, anyHash(), OffsetDateTime.now().minusSeconds(1));
        when(repository.findByTokenHash(anyString())).thenReturn(Optional.of(expired));

        assertThat(service.consumeIfUsable("raw-token")).isEmpty();
    }

    @Test
    void consumeIfUsableReturnsEmptyForAlreadyRevokedToken() {
        RefreshToken revoked = new RefreshToken(42L, anyHash(), OffsetDateTime.now().plusDays(1));
        revoked.revoke();
        when(repository.findByTokenHash(anyString())).thenReturn(Optional.of(revoked));

        assertThat(service.consumeIfUsable("raw-token")).isEmpty();
    }

    @Test
    void revokeAllForUserRevokesEveryActiveToken() {
        RefreshToken a = new RefreshToken(42L, "hash-a", OffsetDateTime.now().plusDays(1));
        RefreshToken b = new RefreshToken(42L, "hash-b", OffsetDateTime.now().plusDays(1));
        when(repository.findByAppUserIdAndRevokedAtIsNull(42L)).thenReturn(List.of(a, b));

        service.revokeAllForUser(42L);

        assertThat(a.getRevokedAt()).isNotNull();
        assertThat(b.getRevokedAt()).isNotNull();
        verify(repository).saveAll(List.of(a, b));
    }

    private String anyHash() {
        return "hash";
    }
}
