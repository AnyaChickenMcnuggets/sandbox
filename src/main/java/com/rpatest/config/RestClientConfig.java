package com.rpatest.config;

import com.rpatest.orchestrator.auth.BearerTokenInterceptor;
import java.net.http.HttpClient;
import java.time.Duration;
import javax.net.ssl.SSLContext;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(OrchestratorProperties.class)
public class RestClientConfig {

    @Bean
    public ClientHttpRequestFactory orchestratorRequestFactory(
            OrchestratorProperties properties, OrchestratorTrustStoreFactory trustStoreFactory) {
        Duration connectTimeout = properties.getHttp().getConnectTimeout();
        Duration readTimeout = properties.getHttp().getReadTimeout();

        var trustedCertificates = properties.getTls().getTrustedCertificates();
        if (trustedCertificates.isEmpty()) {
            ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.DEFAULTS
                    .withConnectTimeout(connectTimeout)
                    .withReadTimeout(readTimeout);
            return ClientHttpRequestFactories.get(settings);
        }

        SSLContext sslContext = trustStoreFactory.buildSslContext(trustedCertificates);
        HttpClient httpClient = HttpClient.newBuilder()
                .sslContext(sslContext)
                .connectTimeout(connectTimeout)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(readTimeout);
        return requestFactory;
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
