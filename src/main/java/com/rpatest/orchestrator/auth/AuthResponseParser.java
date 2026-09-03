package com.rpatest.orchestrator.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rpatest.orchestrator.exception.OrchestratorAuthException;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * POST /api/Account в swagger не описывает схему ответа (только "200 OK").
 * На практике оркестраторы этого типа отдают либо голую строку с JWT, либо JSON-объект
 * с полем токена под одним из распространённых имён. Оба варианта поддержаны, чтобы не
 * ломаться на конкретном стенде — см. риск в roadmap.md.
 */
@Component
public class AuthResponseParser {

    private static final List<String> TOKEN_FIELD_NAMES =
            List.of("token", "accessToken", "access_token", "jwt", "value");

    private final ObjectMapper objectMapper;

    public AuthResponseParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String extractToken(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            throw new OrchestratorAuthException("Пустой ответ от оркестратора при аутентификации");
        }
        String trimmed = rawBody.trim();
        if (looksLikeJson(trimmed)) {
            return extractFromJson(trimmed);
        }
        return stripQuotes(trimmed);
    }

    private boolean looksLikeJson(String value) {
        return value.startsWith("{") || value.startsWith("\"");
    }

    private String extractFromJson(String value) {
        try {
            JsonNode node = objectMapper.readTree(value);
            if (node.isTextual()) {
                return node.asText();
            }
            for (String field : TOKEN_FIELD_NAMES) {
                JsonNode candidate = node.get(field);
                if (candidate != null && candidate.isTextual() && !candidate.asText().isBlank()) {
                    return candidate.asText();
                }
            }
        } catch (Exception e) {
            throw new OrchestratorAuthException("Не удалось разобрать ответ аутентификации оркестратора", e);
        }
        throw new OrchestratorAuthException("Ответ аутентификации не содержит распознаваемого поля токена: " + value);
    }

    private String stripQuotes(String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }
}
