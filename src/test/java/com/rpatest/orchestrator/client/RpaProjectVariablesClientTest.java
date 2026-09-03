package com.rpatest.orchestrator.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.rpatest.orchestrator.dto.RpaProjectVariableDto;
import com.rpatest.orchestrator.dto.RpaProjectVariableEditByIdDto;
import com.rpatest.orchestrator.exception.OrchestratorApiException;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

class RpaProjectVariablesClientTest {

    private WireMockServer wireMockServer;
    private RpaProjectVariablesClient client;

    @BeforeEach
    void setUp() {
        wireMockServer = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMockServer.start();
        RestClient restClient = RestClient.builder()
                .baseUrl(wireMockServer.baseUrl())
                .requestFactory(new SimpleClientHttpRequestFactory())
                .build();
        client = new RpaProjectVariablesClient(restClient);
    }

    @AfterEach
    void tearDown() {
        wireMockServer.stop();
    }

    @Test
    void getReturnsVariablesForAssignment() {
        wireMockServer.stubFor(get(urlEqualTo("/api/RpaProjectVariables/Assignment/42"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("[{\"id\":1,\"name\":\"x\",\"value\":\"0\"}]")));

        List<RpaProjectVariableDto> result = client.get(42);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).name()).isEqualTo("x");
    }

    @Test
    void updateSendsEditsForAssignment() {
        wireMockServer.stubFor(put(urlEqualTo("/api/RpaProjectVariables/Assignment/42"))
                .willReturn(aResponse().withStatus(200)));

        client.update(42, List.of(new RpaProjectVariableEditByIdDto(1, "1")));

        wireMockServer.verify(1, com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor(urlEqualTo("/api/RpaProjectVariables/Assignment/42")));
    }

    @Test
    void wrapsServerErrorIntoOrchestratorApiException() {
        wireMockServer.stubFor(get(urlEqualTo("/api/RpaProjectVariables/Assignment/99"))
                .willReturn(aResponse().withStatus(500)));

        assertThatThrownBy(() -> client.get(99)).isInstanceOf(OrchestratorApiException.class);
    }
}
