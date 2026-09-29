package com.rpatest.auth.web;

import com.rpatest.auth.service.AuthService;

public record TokenResponse(String accessToken, String refreshToken, long expiresInSeconds) {

    public static TokenResponse from(AuthService.TokenPair pair) {
        return new TokenResponse(pair.accessToken(), pair.refreshToken(), pair.expiresInSeconds());
    }
}
