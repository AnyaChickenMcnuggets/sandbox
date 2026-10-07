package com.rpatest.auth.web;

import com.rpatest.auth.domain.Role;
import com.rpatest.auth.service.AuthService;
import com.rpatest.auth.service.AuthenticationRoles;
import com.rpatest.auth.service.InvalidCredentialsException;
import com.rpatest.auth.service.RolePermissionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.TreeSet;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;
    private final AuthCookies authCookies;
    private final RolePermissionService rolePermissionService;

    public AuthController(
            AuthService authService, AuthCookies authCookies, RolePermissionService rolePermissionService) {
        this.authService = authService;
        this.authCookies = authCookies;
        this.rolePermissionService = rolePermissionService;
    }

    @PostMapping("/login")
    public ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest request) {
        AuthService.TokenPair pair = authService.login(request.username(), request.password());
        return withTokenCookies(pair);
    }

    /** Refresh-токен приходит только из куки (см. {@link AuthCookies}), не из тела — после
     * перехода на `HttpOnly`-куки фронту нечего было бы подставить в тело, он его не видит. */
    @PostMapping("/refresh")
    public ResponseEntity<TokenResponse> refresh(HttpServletRequest request) {
        String refreshToken = authCookies.readRefreshToken(request)
                .orElseThrow(() -> new InvalidCredentialsException("Refresh-токен недействителен"));
        AuthService.TokenPair pair = authService.refresh(refreshToken);
        return withTokenCookies(pair);
    }

    /** Единственный способ фронту узнать свою роль (Sprint 27) — токен в {@code HttpOnly}-куке,
     * декодировать JWT на клиенте для этого больше нельзя. Защищён общим правилом {@code
     * anyRequest().authenticated()} в {@code SecurityConfig} (не под {@code /api/v1/auth/**}
     * в {@code permitAll}-матчере — тот сужен до {@code POST}, этот эндпоинт {@code GET}), любая
     * роль, не только конкретная — это "кто я", не операция, требующая прав. */
    @GetMapping("/me")
    public MeResponse me() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        Role role = AuthenticationRoles.roleOf(authentication)
                .orElseThrow(() -> new IllegalStateException("Authenticated request without a role"));
        return new MeResponse(authentication.getName(), role, new TreeSet<>(rolePermissionService.permissionsOf(role)));
    }

    /** Смена СВОЕГО пароля (любая аутентифицированная роль, отдельное право не нужно) — в отличие
     * от {@code PUT /api/v1/admin/users/{id}/password} (сброс админом) требует текущий пароль.
     * Ответ — как у login: текущей сессии выдаётся новая пара кук, остальные сессии отозваны. */
    @PostMapping("/change-password")
    public ResponseEntity<TokenResponse> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        return withTokenCookies(authService.changePassword(username, request.currentPassword(), request.newPassword()));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        authCookies.readRefreshToken(request).ifPresent(authService::logout);
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, authCookies.clearAccessTokenCookie().toString())
                .header(HttpHeaders.SET_COOKIE, authCookies.clearRefreshTokenCookie().toString())
                .build();
    }

    private ResponseEntity<TokenResponse> withTokenCookies(AuthService.TokenPair pair) {
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, authCookies.accessTokenCookie(pair.accessToken()).toString())
                .header(HttpHeaders.SET_COOKIE, authCookies.refreshTokenCookie(pair.refreshToken()).toString())
                .body(TokenResponse.from(pair));
    }
}
