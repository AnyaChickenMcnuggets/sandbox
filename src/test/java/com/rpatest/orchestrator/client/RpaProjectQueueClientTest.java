package com.rpatest.orchestrator.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.rpatest.orchestrator.dto.QueueItemProjectDto;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

class RpaProjectQueueClientTest {

    private WireMockServer wireMockServer;
    private RpaProjectQueueClient client;

    @BeforeEach
    void setUp() {
        wireMockServer = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMockServer.start();
        RestClient restClient = RestClient.builder()
                .baseUrl(wireMockServer.baseUrl())
                .requestFactory(new SimpleClientHttpRequestFactory())
                .build();
        client = new RpaProjectQueueClient(restClient);
    }

    @AfterEach
    void tearDown() {
        wireMockServer.stop();
    }

    @Test
    void returnsQueueEntriesForAssignment() {
        wireMockServer.stubFor(get(urlPathEqualTo("/api/RpaProjectQueue"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("""
                                [{"id":1,"assignmentId":42,"errorMsg":"boom","errorRobotName":"robot-1",
                                  "createdAt":"2026-01-01T10:00:00","updatedAt":"2026-01-01T10:01:00"}]""")));

        List<QueueItemProjectDto> result = client.findByAssignment(42);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).errorMsg()).isEqualTo("boom");
    }

    @Test
    void returnsEmptyListWhenNothingQueued() {
        wireMockServer.stubFor(get(urlPathEqualTo("/api/RpaProjectQueue"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("[]")));

        List<QueueItemProjectDto> result = client.findByAssignment(99);

        assertThat(result).isEmpty();
    }
}
