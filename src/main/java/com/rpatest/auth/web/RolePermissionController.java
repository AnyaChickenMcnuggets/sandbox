package com.rpatest.auth.web;

import com.rpatest.auth.domain.Permission;
import com.rpatest.auth.domain.Role;
import com.rpatest.auth.service.RolePermissionService;
import jakarta.validation.Valid;
import java.util.Arrays;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Управление матрицей "роль — права" (ADR 0005). Доступ — право {@code ROLE_MANAGE}, привязка в
 * {@code SecurityConfig} (`/api/v1/admin/roles/**`, `/api/v1/admin/permissions`). */
@RestController
public class RolePermissionController {

    private final RolePermissionService rolePermissionService;

    public RolePermissionController(RolePermissionService rolePermissionService) {
        this.rolePermissionService = rolePermissionService;
    }

    /** Справочник всех существующих прав — фронту для построения матрицы-таблицы. */
    @GetMapping("/api/v1/admin/permissions")
    public List<PermissionResponse> permissions() {
        return Arrays.stream(Permission.values()).map(PermissionResponse::from).toList();
    }

    @GetMapping("/api/v1/admin/roles")
    public List<RolePermissionsResponse> roles() {
        return Arrays.stream(Role.values())
                .map(role -> RolePermissionsResponse.of(role, rolePermissionService.permissionsOf(role)))
                .toList();
    }

    @PutMapping("/api/v1/admin/roles/{role}/permissions")
    public RolePermissionsResponse setPermissions(
            @PathVariable Role role, @Valid @RequestBody SetRolePermissionsRequest request) {
        return RolePermissionsResponse.of(role, rolePermissionService.replacePermissions(role, request.permissions()));
    }
}
