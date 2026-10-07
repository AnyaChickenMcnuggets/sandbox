package com.rpatest.auth.web;

import com.rpatest.auth.domain.Permission;
import com.rpatest.auth.domain.Role;
import java.util.Set;

/** Ответ {@code GET /api/v1/auth/me} — единственный способ фронту узнать свою роль и права с
 * Sprint 27: токен лежит в {@code HttpOnly}-куке, декодировать JWT на клиенте нельзя. UI-гейтинг
 * делайте по {@code permissions} (они редактируются админом, ADR 0005), а не по имени роли. */
public record MeResponse(String username, Role role, Set<Permission> permissions) {
}
