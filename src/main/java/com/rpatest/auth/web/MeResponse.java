package com.rpatest.auth.web;

import com.rpatest.auth.domain.Role;

/** Ответ {@code GET /api/v1/auth/me} — единственный способ фронту узнать свою роль с Sprint 27:
 * токен лежит в {@code HttpOnly}-куке, декодировать JWT на клиенте для роли больше нельзя. */
public record MeResponse(String username, Role role) {
}
