package com.rpatest.orchestrator.exception;

public class OrchestratorApiException extends RuntimeException {

    public OrchestratorApiException(String message) {
        super(message);
    }

    public OrchestratorApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
