package com.rpatest.auth.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ResetPasswordRequest(
        @NotBlank @Size(min = 8, message = "пароль должен быть не короче 8 символов") String newPassword) {
}
