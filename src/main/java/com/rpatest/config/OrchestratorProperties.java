package com.rpatest.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "orchestrator")
public class OrchestratorProperties {

    private String baseUrl;
    private Credentials credentials = new Credentials();
    private Http http = new Http();
    private Polling polling = new Polling();
    private Polling queueCheckPolling = new Polling(Duration.ofSeconds(5));
    private Tls tls = new Tls();

    /** Минимум свободных (Idle) роботов на оркестраторе, при котором разрешён запуск сценария —
     * иначе {@code ExecutionService.startRun} блокирует запуск (409), чтобы не ставить задания в
     * очередь заведомо без шанса на скорое исполнение. */
    private int minFreeRobots = 2;

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public int getMinFreeRobots() {
        return minFreeRobots;
    }

    public void setMinFreeRobots(int minFreeRobots) {
        this.minFreeRobots = minFreeRobots;
    }

    public Credentials getCredentials() {
        return credentials;
    }

    public void setCredentials(Credentials credentials) {
        this.credentials = credentials;
    }

    public Http getHttp() {
        return http;
    }

    public void setHttp(Http http) {
        this.http = http;
    }

    public Polling getPolling() {
        return polling;
    }

    public void setPolling(Polling polling) {
        this.polling = polling;
    }

    public Polling getQueueCheckPolling() {
        return queueCheckPolling;
    }

    public void setQueueCheckPolling(Polling queueCheckPolling) {
        this.queueCheckPolling = queueCheckPolling;
    }

    public Tls getTls() {
        return tls;
    }

    public void setTls(Tls tls) {
        this.tls = tls;
    }

    public static class Credentials {
        private String username;
        private String password;

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }
    }

    public static class Http {
        private Duration connectTimeout = Duration.ofSeconds(5);
        private Duration readTimeout = Duration.ofSeconds(30);

        public Duration getConnectTimeout() {
            return connectTimeout;
        }

        public void setConnectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
        }

        public Duration getReadTimeout() {
            return readTimeout;
        }

        public void setReadTimeout(Duration readTimeout) {
            this.readTimeout = readTimeout;
        }
    }

    /** Только интервал опроса — таймаута ожидания здесь намеренно нет (ADR 0005): ограничение по
     * времени задаётся исключительно в конфиге шага сценария ({@code timeoutSeconds}), без скрытых
     * глобальных дефолтов. */
    public static class Polling {
        private Duration interval = Duration.ofSeconds(5);

        public Polling() {
        }

        public Polling(Duration interval) {
            this.interval = interval;
        }

        public Duration getInterval() {
            return interval;
        }

        public void setInterval(Duration interval) {
            this.interval = interval;
        }
    }

    public static class Tls {
        /**
         * Пути к сертификатам корневого/промежуточного CA оркестратора (например,
         * {@code file:C:/certs/CA.crt}, {@code file:C:/certs/CA_2.crt}) — нужны, когда
         * оркестратор поднят с сертификатом, подписанным внутренним CA, недоверенным JDK
         * по умолчанию. Пусто — используется системное доверенное хранилище JDK как есть.
         */
        private List<String> trustedCertificates = List.of();

        public List<String> getTrustedCertificates() {
            return trustedCertificates;
        }

        public void setTrustedCertificates(List<String> trustedCertificates) {
            this.trustedCertificates = trustedCertificates;
        }
    }
}
