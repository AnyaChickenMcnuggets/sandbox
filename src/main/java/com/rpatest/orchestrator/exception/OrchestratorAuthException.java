package com.rpatest.orchestrator.exception;

public class OrchestratorAuthException extends RuntimeException {

    public OrchestratorAuthException(String message) {
        super(message);
    }

    public OrchestratorAuthException(String message, Throwable cause) {
        super(message, cause);
    }
}
