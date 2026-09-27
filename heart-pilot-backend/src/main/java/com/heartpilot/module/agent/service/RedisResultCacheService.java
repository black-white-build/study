package com.heartpilot.module.agent.service;

import java.util.Optional;

/**
 * Redis 结果缓存服务。
 * 缓存两类结果以降低大模型/外部接口成本：
 * - knowledge：结构化知识对象（可反序列化为指定类型）
 * - modelResult：大模型原始文本结果
 * key 由 keyMaterial 经 digest 归一化生成，避免不同写法命中不到缓存。
 */
public interface RedisResultCacheService {
    /**
     * 读取缓存的结构化知识对象。
     * @param keyMaterial 缓存键原始材料
     * @param type 期望反序列化的类型
     * @param <T> 泛型
     * @return 命中则返回对象，未命中返回 empty
     */
    <T> Optional<T> getKnowledge(String keyMaterial, Class<T> type);

    /** 写入结构化知识对象缓存 */
    void putKnowledge(String keyMaterial, Object value);

    /** 读取缓存的大模型文本结果，未命中返回 empty */
    Optional<String> getModelResult(String keyMaterial);

    /** 写入大模型文本结果缓存 */
    void putModelResult(String keyMaterial, String value);

    /**
     * 把任意键材料归一化为稳定的缓存 key（通常取哈希摘要）。
     * @param value 原始键材料
     * @return 归一化后的缓存 key
     */
    String digest(String value);
}
