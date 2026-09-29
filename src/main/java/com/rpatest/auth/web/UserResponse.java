package com.rpatest.auth.web;

import com.rpatest.auth.domain.AppUser;
import com.rpatest.auth.domain.Role;
import java.time.OffsetDateTime;

/** Никогда не содержит {@code passwordHash} — только то, что безопасно вернуть по API. */
public record UserResponse(Long id, String username, Role role, boolean enabled, OffsetDateTime createdAt) {

    public static UserResponse from(AppUser user) {
        return new UserResponse(user.getId(), user.getUsername(), user.getRole(), user.isEnabled(), user.getCreatedAt());
    }
}
