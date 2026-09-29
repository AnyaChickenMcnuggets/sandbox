package com.rpatest.auth.web;

import com.rpatest.auth.domain.Role;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateUserRequest(
        @NotBlank String username,
        @NotBlank @Size(min = 8, message = "пароль должен быть не короче 8 символов") String password,
        @NotNull Role role) {
}
