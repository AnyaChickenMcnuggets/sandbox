package com.rpatest.orchestrator.auth;

import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.support.HttpRequestWrapper;
import org.springframework.stereotype.Component;

/** Добавляет Bearer-токен ко всем запросам к оркестратору; на 401 форсирует релогин и повторяет запрос один раз. */
@Component
public class BearerTokenInterceptor implements ClientHttpRequestInterceptor {

    private final OrchestratorAuthService authService;

    public BearerTokenInterceptor(OrchestratorAuthService authService) {
        this.authService = authService;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        HttpRequest authorized = withAuthorization(request, authService.getValidToken());
        ClientHttpResponse response = execution.execute(authorized, body);
        if (response.getStatusCode() == HttpStatus.UNAUTHORIZED) {
            response.close();
            HttpRequest reAuthorized = withAuthorization(request, authService.forceRelogin());
            return execution.execute(reAuthorized, body);
        }
        return response;
    }

    private HttpRequest withAuthorization(HttpRequest original, String token) {
        return new HttpRequestWrapper(original) {
            @Override
            public HttpHeaders getHeaders() {
                HttpHeaders headers = new HttpHeaders();
                headers.addAll(original.getHeaders());
                headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + token);
                return headers;
            }
        };
    }
}
