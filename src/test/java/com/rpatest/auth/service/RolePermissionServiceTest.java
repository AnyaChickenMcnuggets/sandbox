package com.rpatest.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rpatest.auth.domain.Permission;
import com.rpatest.auth.domain.Role;
import com.rpatest.auth.domain.RolePermission;
import com.rpatest.auth.repository.RolePermissionRepository;
import com.rpatest.common.exception.InvalidRequestException;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RolePermissionServiceTest {

    private RolePermissionRepository repository;
    private RolePermissionService service;

    @BeforeEach
    void setUp() {
        repository = mock(RolePermissionRepository.class);
        service = new RolePermissionService(repository);
    }

    @Test
    void adminAlwaysHasEveryPermissionWithoutTouchingDatabase() {
        assertThat(service.permissionsOf(Role.ADMIN)).containsExactlyInAnyOrder(Permission.values());

        verify(repository, never()).findByRole(any());
    }

    @Test
    void loadsPermissionsOfNonAdminRoleFromRepository() {
        when(repository.findByRole(Role.VIEWER)).thenReturn(List.of(
                new RolePermission(Role.VIEWER, Permission.SCENARIO_READ),
                new RolePermission(Role.VIEWER, Permission.RUN_READ)));

        assertThat(service.permissionsOf(Role.VIEWER))
                .containsExactlyInAnyOrder(Permission.SCENARIO_READ, Permission.RUN_READ);
    }

    @Test
    void cachesLoadedPermissionsBetweenCalls() {
        when(repository.findByRole(Role.VIEWER)).thenReturn(List.of(new RolePermission(Role.VIEWER, Permission.RUN_READ)));

        service.permissionsOf(Role.VIEWER);
        service.permissionsOf(Role.VIEWER);

        verify(repository, times(1)).findByRole(Role.VIEWER);
    }

    @Test
    void hasReflectsRolePermissions() {
        when(repository.findByRole(Role.OPERATOR)).thenReturn(List.of(new RolePermission(Role.OPERATOR, Permission.RUN_START)));

        assertThat(service.has(Role.OPERATOR, Permission.RUN_START)).isTrue();
        assertThat(service.has(Role.OPERATOR, Permission.SCENARIO_DELETE)).isFalse();
    }

    @Test
    void replaceDeletesOldRowsSavesNewAndEvictsCache() {
        when(repository.findByRole(Role.OPERATOR))
                .thenReturn(List.of(new RolePermission(Role.OPERATOR, Permission.RUN_START)))
                .thenReturn(List.of(new RolePermission(Role.OPERATOR, Permission.SCENARIO_READ)));
        assertThat(service.permissionsOf(Role.OPERATOR)).containsExactly(Permission.RUN_START);

        Set<Permission> result = service.replacePermissions(Role.OPERATOR, EnumSet.of(Permission.SCENARIO_READ));

        assertThat(result).containsExactly(Permission.SCENARIO_READ);
        verify(repository).deleteByRole(Role.OPERATOR);
        verify(repository).saveAll(any());
        // кэш сброшен — следующее чтение снова идёт в репозиторий и видит новое состояние
        assertThat(service.permissionsOf(Role.OPERATOR)).containsExactly(Permission.SCENARIO_READ);
    }

    @Test
    void replaceWithEmptySetIsAllowedAndLeavesRoleWithoutPermissions() {
        Set<Permission> result = service.replacePermissions(Role.VIEWER, EnumSet.noneOf(Permission.class));

        assertThat(result).isEmpty();
        verify(repository).deleteByRole(Role.VIEWER);
    }

    @Test
    void adminPermissionsCannotBeReplaced() {
        assertThatThrownBy(() -> service.replacePermissions(Role.ADMIN, EnumSet.noneOf(Permission.class)))
                .isInstanceOf(InvalidRequestException.class);

        verify(repository, never()).deleteByRole(any());
    }

    @Test
    void matrixContainsEveryRole() {
        when(repository.findByRole(any())).thenReturn(List.of());

        assertThat(service.matrix()).containsOnlyKeys(Role.values());
    }
}
