package com.rpatest.auth.service;

import com.rpatest.auth.domain.AppUser;
import com.rpatest.auth.domain.Role;
import com.rpatest.auth.repository.AppUserRepository;
import com.rpatest.common.exception.ConflictException;
import com.rpatest.common.exception.NotFoundException;
import java.util.List;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Единственный путь создания/изменения пользователей — нет самостоятельной регистрации (см. ADR
 * 0003), только через этот сервис, вызываемый {@code AdminUserController} (`ADMIN`-only). */
@Service
public class AppUserService {

    private final AppUserRepository repository;
    private final RefreshTokenService refreshTokenService;
    private final PasswordEncoder passwordEncoder;

    public AppUserService(
            AppUserRepository repository, RefreshTokenService refreshTokenService, PasswordEncoder passwordEncoder) {
        this.repository = repository;
        this.refreshTokenService = refreshTokenService;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public AppUser create(String username, String rawPassword, Role role) {
        if (repository.existsByUsername(username)) {
            throw new ConflictException("Пользователь '" + username + "' уже существует");
        }
        AppUser user = new AppUser(username, passwordEncoder.encode(rawPassword), role);
        return repository.save(user);
    }

    @Transactional(readOnly = true)
    public List<AppUser> list() {
        return repository.findAll();
    }

    @Transactional(readOnly = true)
    public AppUser get(Long id) {
        return findOrThrow(id);
    }

    @Transactional
    public AppUser changeRole(Long id, Role role) {
        AppUser user = findOrThrow(id);
        user.changeRole(role);
        return user;
    }

    /** Отключение — не удаление (см. ADR 0003: удаление осиротило бы `ScenarioRun.triggeredBy` так
     * же, как `agents.md` запрещает для `scenario`/`step`). Дополнительно обесценивает все
     * действующие refresh-токены — иначе отключение блокирует только будущий логин, а уже выданный
     * access-токен (до 15 минут) и refresh-токен продолжали бы работать. */
    @Transactional
    public AppUser setEnabled(Long id, boolean enabled) {
        AppUser user = findOrThrow(id);
        user.setEnabled(enabled);
        if (!enabled) {
            refreshTokenService.revokeAllForUser(id);
        }
        return user;
    }

    @Transactional
    public void resetPassword(Long id, String newRawPassword) {
        AppUser user = findOrThrow(id);
        user.changePasswordHash(passwordEncoder.encode(newRawPassword));
        refreshTokenService.revokeAllForUser(id);
    }

    private AppUser findOrThrow(Long id) {
        return repository.findById(id).orElseThrow(() -> new NotFoundException("Пользователь " + id + " не найден"));
    }
}
