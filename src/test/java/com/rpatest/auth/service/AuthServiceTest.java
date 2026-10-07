package com.rpatest.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rpatest.auth.domain.AppUser;
import com.rpatest.auth.domain.Role;
import com.rpatest.auth.repository.AppUserRepository;
import com.rpatest.common.exception.InvalidRequestException;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

class AuthServiceTest {

    private AppUserRepository appUserRepository;
    private PasswordEncoder passwordEncoder;
    private JwtService jwtService;
    private RefreshTokenService refreshTokenService;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        appUserRepository = mock(AppUserRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        jwtService = mock(JwtService.class);
        refreshTokenService = mock(RefreshTokenService.class);
        authService = new AuthService(appUserRepository, passwordEncoder, jwtService, refreshTokenService);

        when(jwtService.accessTokenTtl()).thenReturn(Duration.ofMinutes(15));
    }

    @Test
    void loginReturnsTokenPairForValidCredentials() {
        AppUser user = enabledUser("alice", "hashed");
        setId(user, 42L);
        when(appUserRepository.findByUsername("alice")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("raw-password", "hashed")).thenReturn(true);
        when(jwtService.issueAccessToken(user)).thenReturn("access-token");
        when(refreshTokenService.issue(42L)).thenReturn("refresh-token");

        AuthService.TokenPair result = authService.login("alice", "raw-password");

        assertThat(result.accessToken()).isEqualTo("access-token");
        assertThat(result.refreshToken()).isEqualTo("refresh-token");
        assertThat(result.expiresInSeconds()).isEqualTo(900);
    }

    @Test
    void loginThrowsForUnknownUsername() {
        when(appUserRepository.findByUsername("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login("ghost", "x")).isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void loginThrowsForWrongPassword() {
        AppUser user = enabledUser("alice", "hashed");
        when(appUserRepository.findByUsername("alice")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong", "hashed")).thenReturn(false);

        assertThatThrownBy(() -> authService.login("alice", "wrong")).isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void loginThrowsForDisabledUserEvenWithCorrectPassword() {
        AppUser user = new AppUser("alice", "hashed", Role.VIEWER);
        user.setEnabled(false);
        when(appUserRepository.findByUsername("alice")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("raw-password", "hashed")).thenReturn(true);

        assertThatThrownBy(() -> authService.login("alice", "raw-password")).isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void refreshIssuesNewPairForUsableToken() {
        AppUser user = enabledUser("alice", "hashed");
        setId(user, 42L);
        when(refreshTokenService.consumeIfUsable("raw-refresh")).thenReturn(Optional.of(42L));
        when(appUserRepository.findById(42L)).thenReturn(Optional.of(user));
        when(jwtService.issueAccessToken(user)).thenReturn("new-access");
        when(refreshTokenService.issue(42L)).thenReturn("new-refresh");

        AuthService.TokenPair result = authService.refresh("raw-refresh");

        assertThat(result.accessToken()).isEqualTo("new-access");
        assertThat(result.refreshToken()).isEqualTo("new-refresh");
    }

    @Test
    void refreshThrowsForUnusableToken() {
        when(refreshTokenService.consumeIfUsable("raw-refresh")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.refresh("raw-refresh")).isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void refreshThrowsWhenUserWasDisabledAfterTokenIssued() {
        AppUser disabled = new AppUser("alice", "hashed", Role.VIEWER);
        disabled.setEnabled(false);
        when(refreshTokenService.consumeIfUsable("raw-refresh")).thenReturn(Optional.of(42L));
        when(appUserRepository.findById(42L)).thenReturn(Optional.of(disabled));

        assertThatThrownBy(() -> authService.refresh("raw-refresh")).isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void changePasswordUpdatesHashRevokesAllSessionsAndIssuesNewPair() {
        AppUser user = enabledUser("alice", "old-hash");
        setId(user, 42L);
        when(appUserRepository.findByUsername("alice")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("current", "old-hash")).thenReturn(true);
        when(passwordEncoder.matches("brand-new-pass", "old-hash")).thenReturn(false);
        when(passwordEncoder.encode("brand-new-pass")).thenReturn("new-hash");
        when(jwtService.issueAccessToken(user)).thenReturn("access");
        when(refreshTokenService.issue(42L)).thenReturn("refresh");

        AuthService.TokenPair result = authService.changePassword("alice", "current", "brand-new-pass");

        assertThat(user.getPasswordHash()).isEqualTo("new-hash");
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(refreshTokenService);
        order.verify(refreshTokenService).revokeAllForUser(42L);
        order.verify(refreshTokenService).issue(42L);
        assertThat(result.accessToken()).isEqualTo("access");
    }

    @Test
    void changePasswordRejectsWrongCurrentPasswordWithoutTouchingState() {
        AppUser user = enabledUser("alice", "old-hash");
        setId(user, 42L);
        when(appUserRepository.findByUsername("alice")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong", "old-hash")).thenReturn(false);

        assertThatThrownBy(() -> authService.changePassword("alice", "wrong", "brand-new-pass"))
                .isInstanceOf(InvalidRequestException.class);

        assertThat(user.getPasswordHash()).isEqualTo("old-hash");
        verify(refreshTokenService, org.mockito.Mockito.never()).revokeAllForUser(42L);
    }

    @Test
    void changePasswordRejectsNewPasswordEqualToCurrent() {
        AppUser user = enabledUser("alice", "old-hash");
        when(appUserRepository.findByUsername("alice")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("same-password", "old-hash")).thenReturn(true);

        assertThatThrownBy(() -> authService.changePassword("alice", "same-password", "same-password"))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void changePasswordRejectsDisabledOrMissingUser() {
        when(appUserRepository.findByUsername("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.changePassword("ghost", "a", "brand-new-pass"))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void logoutDelegatesToRefreshTokenService() {
        authService.logout("raw-refresh");

        verify(refreshTokenService).revoke("raw-refresh");
    }

    private AppUser enabledUser(String username, String passwordHash) {
        return new AppUser(username, passwordHash, Role.VIEWER);
    }

    private void setId(Object entity, Long id) {
        try {
            java.lang.reflect.Field field = entity.getClass().getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
