package com.rpatest.orchestrator.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class JwtExpiryReaderTest {

    private final JwtExpiryReader reader = new JwtExpiryReader(new ObjectMapper());

    @Test
    void readsExpiryFromJwtPayload() {
        long exp = Instant.now().plusSeconds(3600).getEpochSecond();
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("{\"exp\":" + exp + "}").getBytes(StandardCharsets.UTF_8));
        String jwt = "header." + payload + ".signature";

        Instant result = reader.readExpiry(jwt);

        assertThat(result.getEpochSecond()).isEqualTo(exp);
    }

    @Test
    void fallsBackToDefaultWhenTokenIsNotJwt() {
        Instant before = Instant.now();

        Instant result = reader.readExpiry("not-a-jwt-token");

        assertThat(result).isAfter(before);
    }
}
