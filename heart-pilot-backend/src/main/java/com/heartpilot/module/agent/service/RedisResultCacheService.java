package com.heartpilot.module.agent.service;

import java.util.Optional;

public interface RedisResultCacheService {
    <T> Optional<T> getKnowledge(String keyMaterial, Class<T> type);

    void putKnowledge(String keyMaterial, Object value);

    Optional<String> getModelResult(String keyMaterial);

    void putModelResult(String keyMaterial, String value);

    String digest(String value);
}
