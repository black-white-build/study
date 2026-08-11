package com.heartpilot.module.agent.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class RedisResultCacheService {
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final MeterRegistry metrics;
    private final boolean enabled;
    private final Duration knowledgeTtl;
    private final Duration modelTtl;

    public RedisResultCacheService(
            ObjectProvider<StringRedisTemplate> redis,
            ObjectMapper json,
            MeterRegistry metrics,
            @Value("${app.cache.redis-enabled:false}") boolean enabled,
            @Value("${app.cache.knowledge-ttl-minutes:30}") long knowledgeTtlMinutes,
            @Value("${app.cache.model-ttl-minutes:60}") long modelTtlMinutes) {
        this.redis = redis.getIfAvailable();
        this.json = json;
        this.metrics = metrics;
        this.enabled = enabled;
        this.knowledgeTtl = Duration.ofMinutes(Math.max(1, knowledgeTtlMinutes));
        this.modelTtl = Duration.ofMinutes(Math.max(1, modelTtlMinutes));
    }

    public <T> Optional<T> getKnowledge(String keyMaterial, Class<T> type) {
        return get("knowledge", keyMaterial, type);
    }

    public void putKnowledge(String keyMaterial, Object value) {
        put("knowledge", keyMaterial, value, knowledgeTtl);
    }

    public Optional<String> getModelResult(String keyMaterial) {
        return get("model", keyMaterial, String.class);
    }

    public void putModelResult(String keyMaterial, String value) {
        put("model", keyMaterial, value, modelTtl);
    }

    public String digest(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private <T> Optional<T> get(String namespace, String material, Class<T> type) {
        if (!enabled || redis == null) return Optional.empty();
        try {
            String value = redis.opsForValue().get(redisKey(namespace, material));
            if (value == null) {
                metrics.counter(
                                "heartpilot.cache.requests",
                                "namespace",
                                namespace,
                                "outcome",
                                "miss")
                        .increment();
                return Optional.empty();
            }
            metrics.counter("heartpilot.cache.requests", "namespace", namespace, "outcome", "hit")
                    .increment();
            return Optional.of(json.readValue(value, type));
        } catch (Exception exception) {
            metrics.counter("heartpilot.cache.requests", "namespace", namespace, "outcome", "error")
                    .increment();
            return Optional.empty();
        }
    }

    private void put(String namespace, String material, Object value, Duration ttl) {
        if (!enabled || redis == null || value == null) return;
        try {
            redis.opsForValue()
                    .set(redisKey(namespace, material), json.writeValueAsString(value), ttl);
            metrics.counter("heartpilot.cache.writes", "namespace", namespace).increment();
        } catch (Exception exception) {
            metrics.counter("heartpilot.cache.requests", "namespace", namespace, "outcome", "error")
                    .increment();
        }
    }

    private String redisKey(String namespace, String material) {
        return "heartpilot:cache:v1:" + namespace + ":" + digest(material);
    }
}
