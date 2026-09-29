package com.rpatest.auth.web;

import com.rpatest.auth.domain.Role;
import jakarta.validation.constraints.NotNull;

public record ChangeRoleRequest(@NotNull Role role) {
}
