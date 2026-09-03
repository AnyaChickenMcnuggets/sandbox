package com.rpatest.orchestrator.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import org.springframework.stereotype.Component;

/**
 * Читает claim {@code exp} из payload JWT без проверки подписи — валидность токена проверяет
 * сам оркестратор на каждом запросе, здесь нужен только ориентир для проактивного обновления.
 */
@Component
public class JwtExpiryReader {

    private final ObjectMapper objectMapper;

    public JwtExpiryReader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Instant readExpiry(String jwt) {
        try {
            String[] parts = jwt.split("\\.");
            if (parts.length < 2) {
                return fallback();
            }
            byte[] payloadBytes = Base64.getUrlDecoder().decode(padBase64(parts[1]));
            JsonNode payload = objectMapper.readTree(new String(payloadBytes, StandardCharsets.UTF_8));
            JsonNode exp = payload.get("exp");
            if (exp != null && exp.canConvertToLong()) {
                return Instant.ofEpochSecond(exp.asLong());
            }
            return fallback();
        } catch (Exception e) {
            return fallback();
        }
    }

    private Instant fallback() {
        return Instant.now().plusSeconds(600);
    }

    private String padBase64(String value) {
        int padding = (4 - value.length() % 4) % 4;
        return value + "=".repeat(padding);
    }
}
