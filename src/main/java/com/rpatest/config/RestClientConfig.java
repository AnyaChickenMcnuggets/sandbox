package com.rpatest.config;

import com.rpatest.orchestrator.auth.BearerTokenInterceptor;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(OrchestratorProperties.class)
public class RestClientConfig {

    @Bean
    public ClientHttpRequestFactory orchestratorRequestFactory(OrchestratorProperties properties) {
        Duration connectTimeout = properties.getHttp().getConnectTimeout();
        Duration readTimeout = properties.getHttp().getReadTimeout();
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.DEFAULTS
                .withConnectTimeout(connectTimeout)
                .withReadTimeout(readTimeout);
        return ClientHttpRequestFactories.get(settings);
    }

    /** Клиент без Bearer-интерцептора — используется только для POST /api/Account. */
    @Bean
    public RestClient orchestratorAuthRestClient(
            OrchestratorProperties properties, ClientHttpRequestFactory orchestratorRequestFactory) {
        return RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .requestFactory(orchestratorRequestFactory)
                .build();
    }

    /** Клиент для всех бизнес-вызовов оркестратора — автоматически подставляет и обновляет токен. */
    @Bean
    public RestClient orchestratorRestClient(
            OrchestratorProperties properties,
            ClientHttpRequestFactory orchestratorRequestFactory,
            BearerTokenInterceptor bearerTokenInterceptor) {
        return RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .requestFactory(orchestratorRequestFactory)
                .requestInterceptor(bearerTokenInterceptor)
                .build();
    }
}
