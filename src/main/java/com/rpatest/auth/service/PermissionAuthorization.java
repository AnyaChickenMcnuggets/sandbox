package com.rpatest.auth.service;

import com.rpatest.auth.domain.Permission;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.stereotype.Component;

/**
 * Фабрика {@link AuthorizationManager} под {@code authorizeHttpRequests} в {@code SecurityConfig}:
 * "у роли текущего пользователя есть право P". Анонимный/неаутентифицированный → отказ (Spring
 * Security сам превратит его в 401, а не 403 — тот же путь, что у {@code authenticated()}).
 */
@Component
public class PermissionAuthorization {

    private final RolePermissionService rolePermissionService;

    public PermissionAuthorization(RolePermissionService rolePermissionService) {
        this.rolePermissionService = rolePermissionService;
    }

    public AuthorizationManager<RequestAuthorizationContext> has(Permission permission) {
        return (authentication, context) -> {
            Authentication auth = authentication.get();
            boolean granted = AuthenticationRoles.roleOf(auth)
                    .map(role -> rolePermissionService.has(role, permission))
                    .orElse(false);
            return new AuthorizationDecision(granted);
        };
    }
}
