package com.rpatest.orchestrator.auth;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

class BearerTokenInterceptorTest {

    private WireMockServer wireMockServer;
    private OrchestratorAuthService authService;
    private RestClient restClient;

    @BeforeEach
    void setUp() {
        wireMockServer = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMockServer.start();
        authService = mock(OrchestratorAuthService.class);
        restClient = RestClient.builder()
                .baseUrl(wireMockServer.baseUrl())
                .requestFactory(new SimpleClientHttpRequestFactory())
                .requestInterceptor(new BearerTokenInterceptor(authService))
                .build();
    }

    @AfterEach
    void tearDown() {
        wireMockServer.stop();
    }

    @Test
    void addsBearerHeaderFromAuthService() {
        when(authService.getValidToken()).thenReturn("token-1");
        wireMockServer.stubFor(get(urlEqualTo("/api/Test"))
                .withHeader("Authorization", equalTo("Bearer token-1"))
                .willReturn(aResponse().withStatus(200)));

        restClient.get().uri("/api/Test").retrieve().toBodilessEntity();

        verify(authService).getValidToken();
    }

    @Test
    void reloginsAndRetriesOn401() {
        when(authService.getValidToken()).thenReturn("expired-token");
        when(authService.forceRelogin()).thenReturn("fresh-token");

        wireMockServer.stubFor(get(urlEqualTo("/api/Test"))
                .inScenario("retry")
                .whenScenarioStateIs(Scenario.STARTED)
                .withHeader("Authorization", equalTo("Bearer expired-token"))
                .willReturn(aResponse().withStatus(401))
                .willSetStateTo("retried"));
        wireMockServer.stubFor(get(urlEqualTo("/api/Test"))
                .inScenario("retry")
                .whenScenarioStateIs("retried")
                .withHeader("Authorization", equalTo("Bearer fresh-token"))
                .willReturn(aResponse().withStatus(200)));

        var response = restClient.get().uri("/api/Test").retrieve().toBodilessEntity();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(authService).forceRelogin();
    }
}
