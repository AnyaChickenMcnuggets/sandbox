package com.rpatest.orchestrator.client;

import com.rpatest.orchestrator.exception.OrchestratorApiException;
import java.util.function.Supplier;
import org.springframework.web.client.RestClientException;

/** Общий перехват сетевых/HTTP ошибок для *Client реализаций — избегаем дублирования try/catch. */
final class OrchestratorClientSupport {

    private OrchestratorClientSupport() {
    }

    static <T> T execute(String operationDescription, Supplier<T> call) {
        try {
            return call.get();
        } catch (RestClientException e) {
            throw new OrchestratorApiException("Ошибка вызова оркестратора: " + operationDescription, e);
        }
    }
}
