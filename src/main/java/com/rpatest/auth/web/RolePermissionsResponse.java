package com.rpatest.auth.web;

import com.rpatest.auth.domain.Permission;
import com.rpatest.auth.domain.Role;
import java.util.Set;
import java.util.TreeSet;

/** @param editable {@code false} для {@code ADMIN} — у него всегда все права (ADR 0005) */
public record RolePermissionsResponse(Role role, Set<Permission> permissions, boolean editable) {

    public static RolePermissionsResponse of(Role role, Set<Permission> permissions) {
        return new RolePermissionsResponse(role, new TreeSet<>(permissions), role != Role.ADMIN);
    }
}
