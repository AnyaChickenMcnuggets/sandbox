package com.rpatest.auth.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rpatest.auth.domain.AppUser;
import com.rpatest.auth.domain.Role;
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
class AppUserRepositoryIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private AppUserRepository repository;

    @Test
    void persistsUserWithRoleAndUniqueUsername() {
        AppUser saved = repository.save(new AppUser("alice", "hash", Role.OPERATOR));

        Optional<AppUser> found = repository.findByUsername("alice");
        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(saved.getId());
        assertThat(found.get().getRole()).isEqualTo(Role.OPERATOR);
        assertThat(found.get().isEnabled()).isTrue();
    }

    @Test
    void existsByUsernameReflectsPersistedRows() {
        repository.save(new AppUser("bob", "hash", Role.VIEWER));

        assertThat(repository.existsByUsername("bob")).isTrue();
        assertThat(repository.existsByUsername("nobody")).isFalse();
    }

    @Test
    void rejectsDuplicateUsername() {
        repository.saveAndFlush(new AppUser("carol", "hash", Role.VIEWER));

        assertThatThrownBy(() -> repository.saveAndFlush(new AppUser("carol", "other-hash", Role.ADMIN)))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
}
