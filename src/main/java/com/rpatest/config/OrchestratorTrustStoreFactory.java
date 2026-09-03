package com.rpatest.config;

import java.io.InputStream;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.List;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

/**
 * Строит SSLContext, доверяющий сертификатам CA оркестратора из
 * {@code orchestrator.tls.trusted-certificates} — нужен, когда оркестратор поднят за
 * внутренним CA, недоверенным стандартным JDK truststore'ом.
 */
@Component
public class OrchestratorTrustStoreFactory {

    private final ResourceLoader resourceLoader = new DefaultResourceLoader();

    public SSLContext buildSslContext(List<String> certificateLocations) {
        try {
            KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
            trustStore.load(null, null);

            CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");
            int index = 0;
            for (String location : certificateLocations) {
                Resource resource = resourceLoader.getResource(location);
                try (InputStream inputStream = resource.getInputStream()) {
                    Certificate certificate = certificateFactory.generateCertificate(inputStream);
                    trustStore.setCertificateEntry("orchestrator-ca-" + index++, certificate);
                }
            }

            TrustManagerFactory trustManagerFactory =
                    TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trustManagerFactory.init(trustStore);

            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustManagerFactory.getTrustManagers(), null);
            return sslContext;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Не удалось построить SSLContext из сертификатов оркестратора: " + certificateLocations, e);
        }
    }
}
