package com.rpatest.orchestrator.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rpatest.orchestrator.exception.OrchestratorAuthException;
import org.junit.jupiter.api.Test;

class AuthResponseParserTest {

    private final AuthResponseParser parser = new AuthResponseParser(new ObjectMapper());

    @Test
    void extractsTokenFromRawJwtString() {
        String jwt = "abc.def.ghi";

        assertThat(parser.extractToken(jwt)).isEqualTo(jwt);
    }

    @Test
    void extractsTokenFromQuotedJsonString() {
        assertThat(parser.extractToken("\"abc.def.ghi\"")).isEqualTo("abc.def.ghi");
    }

    @Test
    void extractsTokenFromJsonObjectWithTokenField() {
        assertThat(parser.extractToken("{\"token\":\"abc.def.ghi\"}")).isEqualTo("abc.def.ghi");
    }

    @Test
    void extractsTokenFromJsonObjectWithAccessTokenField() {
        assertThat(parser.extractToken("{\"accessToken\":\"xyz\"}")).isEqualTo("xyz");
    }

    @Test
    void throwsWhenBodyIsBlank() {
        assertThatThrownBy(() -> parser.extractToken(" ")).isInstanceOf(OrchestratorAuthException.class);
    }

    @Test
    void throwsWhenJsonHasNoRecognizedField() {
        assertThatThrownBy(() -> parser.extractToken("{\"unexpected\":\"value\"}"))
                .isInstanceOf(OrchestratorAuthException.class);
    }
}
