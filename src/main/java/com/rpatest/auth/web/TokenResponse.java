package com.rpatest.auth.web;

import com.rpatest.auth.service.AuthService;

/** Токены сюда не попадают — они уходят клиенту только через `Set-Cookie` (`HttpOnly`, см.
 * {@link AuthCookies}), не JSON-тело, иначе JS на фронте мог бы их прочитать/подменить (ADR 0004). */
public record TokenResponse(long expiresInSeconds) {

    public static TokenResponse from(AuthService.TokenPair pair) {
        return new TokenResponse(pair.expiresInSeconds());
    }
}
