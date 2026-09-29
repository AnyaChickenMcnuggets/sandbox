package com.rpatest.auth.web;

import com.rpatest.auth.domain.AppUser;
import com.rpatest.auth.service.AppUserService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Единственный способ завести пользователя — нет self-registration (см. ADR 0003). Доступ —
 * {@code ADMIN}-only, обеспечивается матрицей в {@code SecurityConfig} (`/api/v1/admin/**`), не
 * аннотацией здесь — один источник правды для всей матрицы доступа. */
@RestController
@RequestMapping("/api/v1/admin/users")
public class AdminUserController {

    private final AppUserService appUserService;

    public AdminUserController(AppUserService appUserService) {
        this.appUserService = appUserService;
    }

    @PostMapping
    public ResponseEntity<UserResponse> create(@Valid @RequestBody CreateUserRequest request) {
        AppUser user = appUserService.create(request.username(), request.password(), request.role());
        return ResponseEntity.status(HttpStatus.CREATED).body(UserResponse.from(user));
    }

    @GetMapping
    public List<UserResponse> list() {
        return appUserService.list().stream().map(UserResponse::from).toList();
    }

    @GetMapping("/{id}")
    public UserResponse get(@PathVariable Long id) {
        return UserResponse.from(appUserService.get(id));
    }

    @PutMapping("/{id}/role")
    public UserResponse changeRole(@PathVariable Long id, @Valid @RequestBody ChangeRoleRequest request) {
        return UserResponse.from(appUserService.changeRole(id, request.role()));
    }

    @PutMapping("/{id}/enabled")
    public UserResponse setEnabled(@PathVariable Long id, @Valid @RequestBody SetEnabledRequest request) {
        return UserResponse.from(appUserService.setEnabled(id, request.enabled()));
    }

    @PutMapping("/{id}/password")
    public ResponseEntity<Void> resetPassword(@PathVariable Long id, @Valid @RequestBody ResetPasswordRequest request) {
        appUserService.resetPassword(id, request.newPassword());
        return ResponseEntity.noContent().build();
    }
}
