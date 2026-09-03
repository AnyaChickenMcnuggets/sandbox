package com.rpatest.orchestrator.auth;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.rpatest.config.OrchestratorProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

class OrchestratorAuthServiceTest {

    private WireMockServer wireMockServer;
    private OrchestratorAuthService authService;

    @BeforeEach
    void setUp() {
        wireMockServer = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMockServer.start();

        RestClient authRestClient = RestClient.builder()
                .baseUrl(wireMockServer.baseUrl())
                .requestFactory(new SimpleClientHttpRequestFactory())
                .build();
        OrchestratorProperties properties = new OrchestratorProperties();
        properties.getCredentials().setUsername("user");
        properties.getCredentials().setPassword("pass");

        ObjectMapper objectMapper = new ObjectMapper();
        authService = new OrchestratorAuthService(
                authRestClient, properties, new AuthResponseParser(objectMapper), new JwtExpiryReader(objectMapper),
                new TokenProvider());
    }

    @AfterEach
    void tearDown() {
        wireMockServer.stop();
    }

    @Test
    void loginParsesRawJwtStringAndCachesToken() {
        wireMockServer.stubFor(post(urlEqualTo("/api/Account"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "text/plain")
                        .withBody("header.payload.signature")));

        String first = authService.getValidToken();
        String second = authService.getValidToken();

        assertThat(first).isEqualTo("header.payload.signature");
        assertThat(second).isEqualTo(first);
        wireMockServer.verify(1, com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor(urlEqualTo("/api/Account")));
    }

    @Test
    void forceReloginCallsAuthEndpointAgain() {
        wireMockServer.stubFor(post(urlEqualTo("/api/Account"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "text/plain")
                        .withBody("token-value")));

        authService.getValidToken();
        authService.forceRelogin();

        wireMockServer.verify(2, com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor(urlEqualTo("/api/Account")));
    }
}
