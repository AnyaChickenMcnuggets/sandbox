package com.rpatest.auth.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.rpatest.auth.domain.AppUser;
import com.rpatest.auth.domain.RefreshToken;
import com.rpatest.auth.domain.Role;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class RefreshTokenRepositoryIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Test
    void persistsTokenAndFindsByHash() {
        AppUser user = appUserRepository.save(new AppUser("alice", "hash", Role.OPERATOR));

        refreshTokenRepository.save(new RefreshToken(user.getId(), "token-hash", OffsetDateTime.now().plusDays(7)));

        Optional<RefreshToken> found = refreshTokenRepository.findByTokenHash("token-hash");
        assertThat(found).isPresent();
        assertThat(found.get().getAppUserId()).isEqualTo(user.getId());
        assertThat(found.get().isUsable()).isTrue();
    }

    @Test
    void findsOnlyActiveTokensForUser() {
        AppUser user = appUserRepository.save(new AppUser("bob", "hash", Role.VIEWER));
        RefreshToken active = refreshTokenRepository.save(
                new RefreshToken(user.getId(), "active-hash", OffsetDateTime.now().plusDays(7)));
        RefreshToken revoked = new RefreshToken(user.getId(), "revoked-hash", OffsetDateTime.now().plusDays(7));
        revoked.revoke();
        refreshTokenRepository.save(revoked);

        List<RefreshToken> found = refreshTokenRepository.findByAppUserIdAndRevokedAtIsNull(user.getId());

        assertThat(found).hasSize(1);
        assertThat(found.get(0).getId()).isEqualTo(active.getId());
    }

    @Test
    void deletingUserCascadesToRefreshTokens() {
        AppUser user = appUserRepository.save(new AppUser("carol", "hash", Role.VIEWER));
        refreshTokenRepository.save(new RefreshToken(user.getId(), "some-hash", OffsetDateTime.now().plusDays(7)));

        appUserRepository.delete(user);
        appUserRepository.flush();

        assertThat(refreshTokenRepository.findByTokenHash("some-hash")).isEmpty();
    }
}
