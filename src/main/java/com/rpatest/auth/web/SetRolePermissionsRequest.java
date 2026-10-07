package com.rpatest.auth.web;

import com.rpatest.auth.domain.Permission;
import jakarta.validation.constraints.NotNull;
import java.util.Set;

/** Полная замена набора прав роли (не дельта) — пустой набор допустим: роль без прав. */
public record SetRolePermissionsRequest(@NotNull Set<Permission> permissions) {
}
