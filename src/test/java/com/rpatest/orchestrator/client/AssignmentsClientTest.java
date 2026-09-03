package com.rpatest.orchestrator.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.rpatest.orchestrator.dto.AssignmentCreateDto;
import com.rpatest.orchestrator.dto.AssignmentDto;
import com.rpatest.orchestrator.exception.OrchestratorApiException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

class AssignmentsClientTest {

    private WireMockServer wireMockServer;
    private AssignmentsClient client;

    @BeforeEach
    void setUp() {
        wireMockServer = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMockServer.start();
        RestClient restClient = RestClient.builder()
                .baseUrl(wireMockServer.baseUrl())
                .requestFactory(new SimpleClientHttpRequestFactory())
                .build();
        client = new AssignmentsClient(restClient);
    }

    @AfterEach
    void tearDown() {
        wireMockServer.stop();
    }

    @Test
    void createReturnsParsedAssignment() {
        wireMockServer.stubFor(post(urlEqualTo("/api/Assignments/v2"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"id":42,"name":"job","description":"d","rpaProjectId":7,"status":0,
                                 "startedAt":null,"stateChangedAt":null,"lastErrorMsg":null}""")));

        AssignmentDto result = client.create(AssignmentCreateDto.manualRun("job", "d", 7));

        assertThat(result.id()).isEqualTo(42);
        assertThat(result.rpaProjectId()).isEqualTo(7);
    }

    @Test
    void getReturnsAssignmentById() {
        wireMockServer.stubFor(get(urlEqualTo("/api/Assignments/v2/42"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"id":42,"name":"job","description":"d","rpaProjectId":7,"status":2,
                                 "startedAt":null,"stateChangedAt":null,"lastErrorMsg":null}""")));

        AssignmentDto result = client.get(42);

        assertThat(result.status().isTerminal()).isTrue();
    }

    @Test
    void startCallsExpectedEndpoint() {
        wireMockServer.stubFor(put(urlEqualTo("/api/Assignments/42/Start")).willReturn(aResponse().withStatus(200)));

        client.start(42);

        wireMockServer.verify(1, com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor(urlEqualTo("/api/Assignments/42/Start")));
    }

    @Test
    void deleteCallsExpectedEndpoint() {
        wireMockServer.stubFor(delete(urlEqualTo("/api/Assignments/42")).willReturn(aResponse().withStatus(200)));

        client.delete(42);

        wireMockServer.verify(1, com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor(urlEqualTo("/api/Assignments/42")));
    }

    @Test
    void wrapsServerErrorIntoOrchestratorApiException() {
        wireMockServer.stubFor(get(urlEqualTo("/api/Assignments/v2/99")).willReturn(aResponse().withStatus(500)));

        assertThatThrownBy(() -> client.get(99)).isInstanceOf(OrchestratorApiException.class);
    }
}
