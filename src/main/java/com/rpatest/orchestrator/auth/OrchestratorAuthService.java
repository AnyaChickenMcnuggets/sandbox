package com.rpatest.orchestrator.auth;

import com.rpatest.config.OrchestratorProperties;
import com.rpatest.orchestrator.dto.LoginDto;
import com.rpatest.orchestrator.exception.OrchestratorAuthException;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Логинится в Primo RPA Orchestrator и держит актуальный токен в {@link TokenProvider}. */
@Service
public class OrchestratorAuthService {

    private final RestClient authRestClient;
    private final OrchestratorProperties properties;
    private final AuthResponseParser authResponseParser;
    private final JwtExpiryReader jwtExpiryReader;
    private final TokenProvider tokenProvider;

    public OrchestratorAuthService(
            @Qualifier("orchestratorAuthRestClient") RestClient authRestClient,
            OrchestratorProperties properties,
            AuthResponseParser authResponseParser,
            JwtExpiryReader jwtExpiryReader,
            TokenProvider tokenProvider) {
        this.authRestClient = authRestClient;
        this.properties = properties;
        this.authResponseParser = authResponseParser;
        this.jwtExpiryReader = jwtExpiryReader;
        this.tokenProvider = tokenProvider;
    }

    public String getValidToken() {
        if (tokenProvider.hasValidToken()) {
            return tokenProvider.getToken();
        }
        tokenProvider.getLock().lock();
        try {
            if (tokenProvider.hasValidToken()) {
                return tokenProvider.getToken();
            }
            return login();
        } finally {
            tokenProvider.getLock().unlock();
        }
    }

    public String forceRelogin() {
        tokenProvider.invalidate();
        return getValidToken();
    }

    @Retry(name = "orchestrator-write")
    private String login() {
        OrchestratorProperties.Credentials credentials = properties.getCredentials();
        LoginDto request = new LoginDto(credentials.getUsername(), credentials.getPassword());
        try {
            String rawBody = authRestClient.post()
                    .uri("/api/Account")
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(String.class);
            String token = authResponseParser.extractToken(rawBody);
            tokenProvider.update(token, jwtExpiryReader.readExpiry(token));
            return token;
        } catch (RestClientException e) {
            throw new OrchestratorAuthException("Не удалось выполнить аутентификацию в оркестраторе", e);
        }
    }
}
