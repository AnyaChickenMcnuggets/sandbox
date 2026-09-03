package com.rpatest.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.Test;

class OrchestratorTrustStoreFactoryTest {

    private final OrchestratorTrustStoreFactory factory = new OrchestratorTrustStoreFactory();

    @Test
    void buildsSslContextFromSingleCertificate() {
        SSLContext context = factory.buildSslContext(List.of("classpath:certs/test-ca.crt"));

        assertThat(context).isNotNull();
        assertThat(context.getProtocol()).isEqualTo("TLS");
    }

    @Test
    void buildsSslContextFromMultipleCertificates() {
        SSLContext context = factory.buildSslContext(List.of("classpath:certs/test-ca.crt", "classpath:certs/test-ca.crt"));

        assertThat(context).isNotNull();
    }

    @Test
    void throwsWhenCertificateLocationIsInvalid() {
        assertThatThrownBy(() -> factory.buildSslContext(List.of("classpath:certs/does-not-exist.crt")))
                .isInstanceOf(IllegalStateException.class);
    }
}
