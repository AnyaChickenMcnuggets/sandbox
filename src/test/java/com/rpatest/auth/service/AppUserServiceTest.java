package com.rpatest.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rpatest.auth.domain.AppUser;
import com.rpatest.auth.domain.Role;
import com.rpatest.auth.repository.AppUserRepository;
import com.rpatest.common.exception.ConflictException;
import com.rpatest.common.exception.NotFoundException;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

class AppUserServiceTest {

    private AppUserRepository repository;
    private RefreshTokenService refreshTokenService;
    private PasswordEncoder passwordEncoder;
    private AppUserService service;

    @BeforeEach
    void setUp() {
        repository = mock(AppUserRepository.class);
        refreshTokenService = mock(RefreshTokenService.class);
        passwordEncoder = mock(PasswordEncoder.class);
        service = new AppUserService(repository, refreshTokenService, passwordEncoder);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void createHashesPasswordAndSaves() {
        when(repository.existsByUsername("alice")).thenReturn(false);
        when(passwordEncoder.encode("raw-password")).thenReturn("hashed");

        AppUser created = service.create("alice", "raw-password", Role.OPERATOR);

        assertThat(created.getUsername()).isEqualTo("alice");
        assertThat(created.getPasswordHash()).isEqualTo("hashed");
        assertThat(created.getRole()).isEqualTo(Role.OPERATOR);
        assertThat(created.isEnabled()).isTrue();
    }

    @Test
    void createThrowsWhenUsernameAlreadyExists() {
        when(repository.existsByUsername("alice")).thenReturn(true);

        assertThatThrownBy(() -> service.create("alice", "x", Role.VIEWER)).isInstanceOf(ConflictException.class);
    }

    @Test
    void listReturnsAllUsers() {
        when(repository.findAll()).thenReturn(List.of(new AppUser("a", "h", Role.VIEWER)));

        assertThat(service.list()).hasSize(1);
    }

    @Test
    void getThrowsWhenNotFound() {
        when(repository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(99L)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void changeRoleUpdatesRole() {
        AppUser user = new AppUser("alice", "h", Role.VIEWER);
        when(repository.findById(1L)).thenReturn(Optional.of(user));

        AppUser result = service.changeRole(1L, Role.ADMIN);

        assertThat(result.getRole()).isEqualTo(Role.ADMIN);
    }

    @Test
    void disablingUserRevokesAllRefreshTokens() {
        AppUser user = new AppUser("alice", "h", Role.OPERATOR);
        setId(user, 5L);
        when(repository.findById(5L)).thenReturn(Optional.of(user));

        AppUser result = service.setEnabled(5L, false);

        assertThat(result.isEnabled()).isFalse();
        verify(refreshTokenService).revokeAllForUser(5L);
    }

    @Test
    void enablingUserDoesNotTouchRefreshTokens() {
        AppUser user = new AppUser("alice", "h", Role.OPERATOR);
        user.setEnabled(false);
        setId(user, 5L);
        when(repository.findById(5L)).thenReturn(Optional.of(user));

        service.setEnabled(5L, true);

        verify(refreshTokenService, never()).revokeAllForUser(5L);
    }

    @Test
    void resetPasswordUpdatesHashAndRevokesRefreshTokens() {
        AppUser user = new AppUser("alice", "old-hash", Role.OPERATOR);
        setId(user, 5L);
        when(repository.findById(5L)).thenReturn(Optional.of(user));
        when(passwordEncoder.encode("new-password")).thenReturn("new-hash");

        service.resetPassword(5L, "new-password");

        assertThat(user.getPasswordHash()).isEqualTo("new-hash");
        verify(refreshTokenService).revokeAllForUser(5L);
    }

    private void setId(Object entity, Long id) {
        try {
            Field field = entity.getClass().getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
