package com.heartpilot.module.agent.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.heartpilot.module.agent.service.RedisResultCacheService;
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

/**
 * 基于 Redis 的结果缓存服务。
 * 缓存两类结果：knowledge（知识/检索类，TTL 较短）与 model（模型生成类，TTL 较长）。
 *
 * 设计要点：
 * - key 用 "heartpilot:cache:v1:<namespace>:<SHA-256(material)>"，
 *   对原始 key 材料做哈希后再拼接，避免长 key 与特殊字符
 * - 未启用/无 Redis 时所有 get 返回空、put 直接跳过，缓存透明降级，不影响业务
 * - 任何 Redis/序列化异常都吞掉并记 error 指标，缓存故障不阻断主流程
 * - 命中率/写入/错误都通过 Micrometer 指标暴露
 */
@Service
public class RedisResultCacheServiceImpl implements RedisResultCacheService {
    /** Redis 客户端，未装配时为 null */
    private final StringRedisTemplate redis;
    /** JSON 序列化 */
    private final ObjectMapper json;
    /** 指标注册中心，统计命中/未命中/错误 */
    private final MeterRegistry metrics;
    /** 缓存总开关，配置项 app.cache.redis-enabled，默认 false */
    private final boolean enabled;
    /** 知识类缓存 TTL，配置项 app.cache.knowledge-ttl-minutes，默认 30 分钟 */
    private final Duration knowledgeTtl;
    /** 模型结果缓存 TTL，配置项 app.cache.model-ttl-minutes，默认 60 分钟 */
    private final Duration modelTtl;

    /**
     * 构造器注入。TTL 至少 1 分钟，防止配 0 导致 key 立即过期。
     */
    public RedisResultCacheServiceImpl(
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
        // 至少 1 分钟，避免 TTL=0 立即过期
        this.knowledgeTtl = Duration.ofMinutes(Math.max(1, knowledgeTtlMinutes));
        this.modelTtl = Duration.ofMinutes(Math.max(1, modelTtlMinutes));
    }

    /** 读知识类缓存，反序列化为指定类型；未命中/异常返回空 */
    @Override
    public <T> Optional<T> getKnowledge(String keyMaterial, Class<T> type) {
        return get("knowledge", keyMaterial, type);
    }

    /** 写知识类缓存，使用 knowledgeTtl 过期 */
    @Override
    public void putKnowledge(String keyMaterial, Object value) {
        put("knowledge", keyMaterial, value, knowledgeTtl);
    }

    /** 读模型结果缓存（字符串） */
    @Override
    public Optional<String> getModelResult(String keyMaterial) {
        return get("model", keyMaterial, String.class);
    }

    /** 写模型结果缓存，使用 modelTtl 过期 */
    @Override
    public void putModelResult(String keyMaterial, String value) {
        put("model", keyMaterial, value, modelTtl);
    }

    /**
     * 对 key 材料做 SHA-256 摘要并转十六进制，作为 Redis key 的一部分。
     * SHA-256 不可用属 JVM 严重问题，直接抛 IllegalStateException。
     */
    @Override
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

    /**
     * 通用读缓存。未启用/无 Redis 返回空；命中记 hit，未命中记 miss，
     * 异常记 error 并返回空——缓存故障不影响主流程。
     */
    private <T> Optional<T> get(String namespace, String material, Class<T> type) {
        if (!enabled || redis == null) return Optional.empty();
        try {
            String value = redis.opsForValue().get(redisKey(namespace, material));
            if (value == null) {
                // 缓存未命中
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
            // 反序列化或 Redis 异常：降级为未命中
            metrics.counter("heartpilot.cache.requests", "namespace", namespace, "outcome", "error")
                    .increment();
            return Optional.empty();
        }
    }

    /** 通用写缓存，带 TTL；异常只记 error 指标，不抛出 */
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

    /** 拼接最终 Redis key：版本号 + 命名空间 + key 材料的 SHA-256 摘要 */
    private String redisKey(String namespace, String material) {
        return "heartpilot:cache:v1:" + namespace + ":" + digest(material);
    }
}
