package com.rpatest.orchestrator.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class TokenProviderTest {

    private final TokenProvider tokenProvider = new TokenProvider();

    @Test
    void hasNoValidTokenInitially() {
        assertThat(tokenProvider.hasValidToken()).isFalse();
    }

    @Test
    void hasValidTokenAfterUpdateWithFutureExpiry() {
        tokenProvider.update("token", Instant.now().plusSeconds(600));

        assertThat(tokenProvider.hasValidToken()).isTrue();
        assertThat(tokenProvider.getToken()).isEqualTo("token");
    }

    @Test
    void tokenIsInvalidWithinSafetyMargin() {
        tokenProvider.update("token", Instant.now().plusSeconds(10));

        assertThat(tokenProvider.hasValidToken()).isFalse();
    }

    @Test
    void invalidateClearsToken() {
        tokenProvider.update("token", Instant.now().plusSeconds(600));

        tokenProvider.invalidate();

        assertThat(tokenProvider.hasValidToken()).isFalse();
        assertThat(tokenProvider.getToken()).isNull();
    }
}
