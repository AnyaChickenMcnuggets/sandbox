package com.rpatest.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rpatest.auth.domain.AppUser;
import com.rpatest.auth.domain.Role;
import com.rpatest.config.AuthProperties;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JwtServiceTest {

    private JwtService jwtService;

    @BeforeEach
    void setUp() {
        AuthProperties properties = new AuthProperties();
        properties.getJwt().setSecret("test-secret-at-least-32-bytes-long!!");
        properties.getJwt().setAccessTokenTtl(Duration.ofMinutes(15));
        jwtService = new JwtService(properties);
        jwtService.init();
    }

    @Test
    void issuedTokenParsesBackToSameUsernameAndRole() {
        AppUser user = new AppUser("alice", "hash", Role.OPERATOR);

        String token = jwtService.issueAccessToken(user);
        JwtService.AccessTokenClaims claims = jwtService.parse(token);

        assertThat(claims).isNotNull();
        assertThat(claims.username()).isEqualTo("alice");
        assertThat(claims.role()).isEqualTo(Role.OPERATOR);
    }

    @Test
    void parseReturnsNullForGarbageToken() {
        assertThat(jwtService.parse("not-a-jwt")).isNull();
    }

    @Test
    void parseReturnsNullForTokenSignedWithDifferentSecret() {
        AuthProperties otherProperties = new AuthProperties();
        otherProperties.getJwt().setSecret("a-completely-different-secret-32b!!");
        JwtService otherService = new JwtService(otherProperties);
        otherService.init();
        String tokenFromOtherService = otherService.issueAccessToken(new AppUser("bob", "hash", Role.VIEWER));

        assertThat(jwtService.parse(tokenFromOtherService)).isNull();
    }

    @Test
    void refusesToStartWithSecretShorterThan32Bytes() {
        AuthProperties properties = new AuthProperties();
        properties.getJwt().setSecret("too-short");
        JwtService shortSecretService = new JwtService(properties);

        assertThatThrownBy(shortSecretService::init).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void refusesToStartWithNoSecret() {
        AuthProperties properties = new AuthProperties();
        JwtService noSecretService = new JwtService(properties);

        assertThatThrownBy(noSecretService::init).isInstanceOf(IllegalStateException.class);
    }
}
