package com.rpatest.auth.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rpatest.auth.domain.Permission;
import com.rpatest.auth.domain.Role;
import com.rpatest.auth.domain.RolePermission;
import java.util.List;
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
class RolePermissionRepositoryIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private RolePermissionRepository repository;

    @Test
    void migrationSeedsDefaultMatrixForViewerAndOperatorButNotAdmin() {
        assertThat(repository.findByRole(Role.VIEWER)).extracting(RolePermission::getPermission)
                .containsExactlyInAnyOrder(Permission.SCENARIO_READ, Permission.RUN_READ, Permission.ORCHESTRATOR_READ);
        assertThat(repository.findByRole(Role.OPERATOR)).extracting(RolePermission::getPermission)
                .contains(Permission.RUN_START, Permission.SCENARIO_WRITE, Permission.CLEANUP)
                .doesNotContain(Permission.SCENARIO_DELETE, Permission.USER_MANAGE);
        assertThat(repository.findByRole(Role.ADMIN)).isEmpty();
    }

    @Test
    void deleteByRoleRemovesOnlyThatRolesRows() {
        repository.deleteByRole(Role.VIEWER);
        repository.flush();

        assertThat(repository.findByRole(Role.VIEWER)).isEmpty();
        assertThat(repository.findByRole(Role.OPERATOR)).isNotEmpty();
    }

    @Test
    void rejectsDuplicateRolePermissionPair() {
        repository.saveAndFlush(new RolePermission(Role.VIEWER, Permission.CLEANUP));

        assertThatThrownBy(() -> repository.saveAndFlush(new RolePermission(Role.VIEWER, Permission.CLEANUP)))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void savesNewPermissionsForRole() {
        repository.saveAll(List.of(new RolePermission(Role.VIEWER, Permission.RUN_START)));
        repository.flush();

        assertThat(repository.findByRole(Role.VIEWER)).extracting(RolePermission::getPermission)
                .contains(Permission.RUN_START);
    }
}
