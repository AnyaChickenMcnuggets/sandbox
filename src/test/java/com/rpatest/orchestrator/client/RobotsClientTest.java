package com.rpatest.orchestrator.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.rpatest.orchestrator.dto.RobotDto;
import com.rpatest.orchestrator.dto.RobotRunStatus;
import com.rpatest.orchestrator.exception.OrchestratorApiException;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

class RobotsClientTest {

    private WireMockServer wireMockServer;
    private RobotsClient client;

    @BeforeEach
    void setUp() {
        wireMockServer = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMockServer.start();
        RestClient restClient = RestClient.builder()
                .baseUrl(wireMockServer.baseUrl())
                .requestFactory(new SimpleClientHttpRequestFactory())
                .build();
        client = new RobotsClient(restClient);
    }

    @AfterEach
    void tearDown() {
        wireMockServer.stop();
    }

    @Test
    void listReturnsRobotsWithStatusFromV2Endpoint() {
        wireMockServer.stubFor(get(urlPathEqualTo("/api/Robots/v2"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"totalCount":2,"filterCount":2,"result":[
                                  {"id":1,"name":"robot-1","status":2},
                                  {"id":2,"name":"robot-2","status":3}
                                ]}""")));

        List<RobotDto> result = client.list();

        assertThat(result).hasSize(2);
        assertThat(result.get(0).status()).isEqualTo(RobotRunStatus.IDLE);
        assertThat(result.get(0).isFree()).isTrue();
        assertThat(result.get(1).status()).isEqualTo(RobotRunStatus.RUNNING);
        assertThat(result.get(1).isFree()).isFalse();
    }

    @Test
    void listReturnsEmptyWhenResultIsNull() {
        wireMockServer.stubFor(get(urlPathEqualTo("/api/Robots/v2"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"totalCount\":0,\"filterCount\":0,\"result\":null}")));

        assertThat(client.list()).isEmpty();
    }

    @Test
    void wrapsServerErrorIntoOrchestratorApiException() {
        wireMockServer.stubFor(get(urlPathEqualTo("/api/Robots/v2")).willReturn(aResponse().withStatus(500)));

        assertThatThrownBy(() -> client.list()).isInstanceOf(OrchestratorApiException.class);
    }
}
