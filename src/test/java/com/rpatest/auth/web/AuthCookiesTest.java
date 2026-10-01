package com.rpatest.auth.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.rpatest.config.AuthProperties;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseCookie;
import org.springframework.mock.web.MockHttpServletRequest;

class AuthCookiesTest {

    @Test
    void accessTokenCookieIsHttpOnlySecureStrictWithValueAndTtl() {
        AuthProperties properties = new AuthProperties();
        properties.getJwt().setAccessTokenTtl(Duration.ofMinutes(15));
        AuthCookies authCookies = new AuthCookies(properties);

        ResponseCookie cookie = authCookies.accessTokenCookie("raw-access");

        assertThat(cookie.getName()).isEqualTo("access_token");
        assertThat(cookie.getValue()).isEqualTo("raw-access");
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.isSecure()).isTrue();
        assertThat(cookie.getSameSite()).isEqualTo("Strict");
        assertThat(cookie.getPath()).isEqualTo("/");
        assertThat(cookie.getMaxAge()).isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    void refreshTokenCookieIsScopedToAuthPath() {
        AuthProperties properties = new AuthProperties();
        properties.getJwt().setRefreshTokenTtl(Duration.ofDays(7));
        AuthCookies authCookies = new AuthCookies(properties);

        ResponseCookie cookie = authCookies.refreshTokenCookie("raw-refresh");

        assertThat(cookie.getName()).isEqualTo("refresh_token");
        assertThat(cookie.getPath()).isEqualTo("/api/v1/auth");
        assertThat(cookie.getMaxAge()).isEqualTo(Duration.ofDays(7));
    }

    @Test
    void secureFlagFollowsConfiguration() {
        AuthProperties properties = new AuthProperties();
        properties.getCookies().setSecure(false);
        AuthCookies authCookies = new AuthCookies(properties);

        assertThat(authCookies.accessTokenCookie("x").isSecure()).isFalse();
    }

    @Test
    void clearCookiesHaveZeroMaxAge() {
        AuthCookies authCookies = new AuthCookies(new AuthProperties());

        assertThat(authCookies.clearAccessTokenCookie().getMaxAge()).isZero();
        assertThat(authCookies.clearRefreshTokenCookie().getMaxAge()).isZero();
    }

    @Test
    void readsAccessTokenFromMatchingCookie() {
        AuthCookies authCookies = new AuthCookies(new AuthProperties());
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie("access_token", "value-a"), new Cookie("other", "value-b"));

        assertThat(authCookies.readAccessToken(request)).contains("value-a");
    }

    @Test
    void readReturnsEmptyWhenNoCookiesPresent() {
        AuthCookies authCookies = new AuthCookies(new AuthProperties());

        assertThat(authCookies.readAccessToken(new MockHttpServletRequest())).isEmpty();
        assertThat(authCookies.readRefreshToken(new MockHttpServletRequest())).isEmpty();
    }
}
