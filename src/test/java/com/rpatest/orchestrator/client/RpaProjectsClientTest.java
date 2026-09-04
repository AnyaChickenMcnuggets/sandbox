package com.rpatest.orchestrator.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.rpatest.orchestrator.dto.RpaProjectShortDto;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

class RpaProjectsClientTest {

    private WireMockServer wireMockServer;
    private RpaProjectsClient client;

    @BeforeEach
    void setUp() {
        wireMockServer = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMockServer.start();
        RestClient restClient = RestClient.builder()
                .baseUrl(wireMockServer.baseUrl())
                .requestFactory(new SimpleClientHttpRequestFactory())
                .build();
        client = new RpaProjectsClient(restClient);
    }

    @AfterEach
    void tearDown() {
        wireMockServer.stop();
    }

    @Test
    void listReturnsAllProjects() {
        wireMockServer.stubFor(get(urlEqualTo("/api/RpaProjects/v3/short"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("""
                                [{"id":1,"name":"Project A","description":null,"parentId":null,"active":true}]""")));

        List<RpaProjectShortDto> result = client.list();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).name()).isEqualTo("Project A");
    }

    @Test
    void findByNamePrefersActiveVersionOnDuplicateNames() {
        wireMockServer.stubFor(get(urlEqualTo("/api/RpaProjects/v3/short"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("""
                                [{"id":1,"name":"Project A","description":null,"parentId":null,"active":false},
                                 {"id":2,"name":"Project A","description":null,"parentId":null,"active":true}]""")));

        Optional<RpaProjectShortDto> result = client.findByName("Project A");

        assertThat(result).isPresent();
        assertThat(result.get().id()).isEqualTo(2);
    }

    @Test
    void findByNameReturnsEmptyWhenNoMatch() {
        wireMockServer.stubFor(get(urlEqualTo("/api/RpaProjects/v3/short"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("[]")));

        assertThat(client.findByName("missing")).isEmpty();
    }
}
