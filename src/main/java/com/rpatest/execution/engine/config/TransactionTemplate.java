package com.rpatest.execution.engine.config;

import java.util.Map;

public record TransactionTemplate(String naturalKey, Object value, Map<String, String> metadata) {
}
