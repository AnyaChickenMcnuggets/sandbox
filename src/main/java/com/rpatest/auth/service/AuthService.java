package com.rpatest.auth.service;

import com.rpatest.auth.domain.AppUser;
import com.rpatest.auth.repository.AppUserRepository;
import com.rpatest.common.exception.InvalidRequestException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Единственная точка входа для логина/обновления/выхода — компонует {@link JwtService} (access)
 * и {@link RefreshTokenService} (refresh), сама не хранит криптографию. */
@Service
public class AuthService {

    private final AppUserRepository appUserRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;

    public AuthService(
            AppUserRepository appUserRepository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            RefreshTokenService refreshTokenService) {
        this.appUserRepository = appUserRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.refreshTokenService = refreshTokenService;
    }

    @Transactional
    public TokenPair login(String username, String password) {
        AppUser user = appUserRepository.findByUsername(username)
                .filter(u -> passwordEncoder.matches(password, u.getPasswordHash()))
                .filter(AppUser::isEnabled)
                .orElseThrow(() -> new InvalidCredentialsException("Неверный логин или пароль"));
        return issueTokenPair(user);
    }

    @Transactional
    public TokenPair refresh(String rawRefreshToken) {
        Long appUserId = refreshTokenService.consumeIfUsable(rawRefreshToken)
                .orElseThrow(() -> new InvalidCredentialsException("Refresh-токен недействителен"));
        AppUser user = appUserRepository.findById(appUserId)
                .filter(AppUser::isEnabled)
                .orElseThrow(() -> new InvalidCredentialsException("Refresh-токен недействителен"));
        return issueTokenPair(user);
    }

    @Transactional
    public void logout(String rawRefreshToken) {
        refreshTokenService.revoke(rawRefreshToken);
    }

    /**
     * Смена собственного пароля: обязательно подтверждение текущим паролем (украденная сессия
     * сама по себе не позволяет угнать аккаунт). Все refresh-токены пользователя отзываются (другие
     * устройства/сессии разлогиниваются), текущей сессии выдаётся новая пара — пользователь не
     * вылетает на экран логина сразу после смены.
     */
    @Transactional
    public TokenPair changePassword(String username, String currentPassword, String newPassword) {
        AppUser user = appUserRepository.findByUsername(username)
                .filter(AppUser::isEnabled)
                .orElseThrow(() -> new InvalidCredentialsException("Пользователь недоступен"));
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new InvalidRequestException("Текущий пароль указан неверно");
        }
        if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
            throw new InvalidRequestException("Новый пароль должен отличаться от текущего");
        }
        user.changePasswordHash(passwordEncoder.encode(newPassword));
        refreshTokenService.revokeAllForUser(user.getId());
        return issueTokenPair(user);
    }

    private TokenPair issueTokenPair(AppUser user) {
        String accessToken = jwtService.issueAccessToken(user);
        String refreshToken = refreshTokenService.issue(user.getId());
        return new TokenPair(accessToken, refreshToken, jwtService.accessTokenTtl().toSeconds());
    }

    public record TokenPair(String accessToken, String refreshToken, long expiresInSeconds) {
    }
}
