package com.rpatest.orchestrator.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.rpatest.orchestrator.dto.EnqueueExchangeQueueDto;
import com.rpatest.orchestrator.dto.ExchangeQueueDto;
import com.rpatest.orchestrator.dto.PageDto;
import com.rpatest.orchestrator.dto.ExchangeQueueValueDto;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

class ExchangeQueuesClientTest {

    private WireMockServer wireMockServer;
    private ExchangeQueuesClient client;

    @BeforeEach
    void setUp() {
        wireMockServer = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMockServer.start();
        RestClient restClient = RestClient.builder()
                .baseUrl(wireMockServer.baseUrl())
                .requestFactory(new SimpleClientHttpRequestFactory())
                .build();
        client = new ExchangeQueuesClient(restClient);
    }

    @AfterEach
    void tearDown() {
        wireMockServer.stop();
    }

    @Test
    void findByNameLocatesQueueFromList() {
        UUID id = UUID.randomUUID();
        wireMockServer.stubFor(get(urlEqualTo("/api/ExchangeQueues"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("[{\"id\":\"" + id + "\",\"name\":\"q1\",\"description\":null,\"countItems\":0,\"countReadedItems\":0}]")));

        Optional<ExchangeQueueDto> result = client.findByName("q1");

        assertThat(result).isPresent();
        assertThat(result.get().id()).isEqualTo(id);
    }

    @Test
    void enqueuePutsToNameBasedEndpoint() {
        wireMockServer.stubFor(put(urlPathEqualTo("/api/ExchangeQueues/v2/enqueue/q1")).willReturn(aResponse().withStatus(200)));

        client.enqueue("q1", EnqueueExchangeQueueDto.of("key-1", "value", null));

        wireMockServer.verify(1, com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor(urlPathEqualTo("/api/ExchangeQueues/v2/enqueue/q1")));
    }

    @Test
    void listItemsReturnsPagedResult() {
        UUID queueId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        wireMockServer.stubFor(get(urlPathEqualTo("/api/ExchangeQueues/" + queueId + "/Items"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"totalCount\":1,\"items\":[{\"id\":\"" + itemId
                                + "\",\"value\":\"v\",\"createdAt\":\"2024-01-01T00:00:00.123456\",\"lastEventType\":0}]}")));

        PageDto<ExchangeQueueValueDto> page = client.listItems(queueId, 0, 100);

        assertThat(page.totalCount()).isEqualTo(1);
        assertThat(page.items()).hasSize(1);
        assertThat(page.items().get(0).id()).isEqualTo(itemId);
    }
}
