package com.rpatest.auth.web;

import com.rpatest.auth.domain.Permission;

public record PermissionResponse(Permission code, String description) {

    public static PermissionResponse from(Permission permission) {
        return new PermissionResponse(permission, permission.description());
    }
}
