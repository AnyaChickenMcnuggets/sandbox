package com.rpatest.orchestrator.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.rpatest.orchestrator.dto.RpaProjectLaunchDto;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

class RpaProjectLaunchesClientTest {

    private WireMockServer wireMockServer;
    private RpaProjectLaunchesClient client;

    @BeforeEach
    void setUp() {
        wireMockServer = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMockServer.start();
        RestClient restClient = RestClient.builder()
                .baseUrl(wireMockServer.baseUrl())
                .requestFactory(new SimpleClientHttpRequestFactory())
                .build();
        client = new RpaProjectLaunchesClient(restClient);
    }

    @AfterEach
    void tearDown() {
        wireMockServer.stop();
    }

    @Test
    void returnsLaunchesForAssignment() {
        wireMockServer.stubFor(get(urlPathEqualTo("/api/RpaProjectLaunches/assignment/42"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"totalCount":1,"filterCount":1,"result":[
                                  {"id":1,"projectId":7,"robotId":3,"robotName":"robot-1","assignmentId":42,
                                   "startedAt":"2026-01-01T10:00:00","completedAt":"2026-01-01T10:05:00",
                                   "success":true,"killedAt":null,"robotStartedAt":"2026-01-01T10:00:30"}
                                ]}""")));

        List<RpaProjectLaunchDto> result = client.getByAssignment(42);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).robotName()).isEqualTo("robot-1");
        assertThat(result.get(0).isSuccess()).isTrue();
        assertThat(result.get(0).isTerminal()).isTrue();
    }

    @Test
    void returnsEmptyListWhenNoLaunchesYet() {
        wireMockServer.stubFor(get(urlPathEqualTo("/api/RpaProjectLaunches/assignment/99"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"totalCount\":0,\"filterCount\":0,\"result\":[]}")));

        List<RpaProjectLaunchDto> result = client.getByAssignment(99);

        assertThat(result).isEmpty();
    }
}
