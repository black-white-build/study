package com.heartpilot.module.usage.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 用量与成本统计相关 DTO 集合。
 * 所有金额均以 micros（百万分之一元）存储，前端展示时再换算，避免浮点误差。
 */
public final class UsageDtos {
    private UsageDtos() {}

    /**
     * 成本看板汇总响应。
     * @param periodStart 统计区间起始时间
     * @param periodEnd 统计区间结束时间
     * @param currency 币种（CNY）
     * @param estimationNote 计费口径说明
     * @param totalRequests 总请求次数（按 ASSISTANT 消息计）
     * @param inputTokens 输入 Token 总量
     * @param outputTokens 输出 Token 总量
     * @param totalCostMicros 估算总成本（micros）
     * @param cacheHits 命中缓存的次数
     * @param cacheHitRate 缓存命中率（cacheHits / totalRequests）
     * @param cacheSavedCostMicros 缓存节省的成本（micros）
     * @param averageProviderLatencyMs 模型接口平均耗时（毫秒）
     * @param daily 按日聚合的成本趋势
     * @param models 按模型分组的成本明细
     */
    public record CostDashboardResponse(
            Instant periodStart,
            Instant periodEnd,
            String currency,
            String estimationNote,
            long totalRequests,
            long inputTokens,
            long outputTokens,
            long totalCostMicros,
            long cacheHits,
            double cacheHitRate,
            long cacheSavedCostMicros,
            long averageProviderLatencyMs,
            List<DailyCost> daily,
            List<ModelCost> models) {}

    /**
     * 单日用量成本聚合。
     * @param date 日期（按东八区归属）
     * @param requests 当日请求次数
     * @param inputTokens 当日输入 Token
     * @param outputTokens 当日输出 Token
     * @param costMicros 当日估算成本（micros）
     * @param cacheHits 当日缓存命中次数
     * @param cacheSavedCostMicros 当日缓存节省成本（micros）
     */
    public record DailyCost(
            LocalDate date,
            long requests,
            long inputTokens,
            long outputTokens,
            long costMicros,
            long cacheHits,
            long cacheSavedCostMicros) {}

    /**
     * 按模型分组的用量成本聚合。
     * @param model 模型名称
     * @param requests 该模型请求次数
     * @param inputTokens 输入 Token
     * @param outputTokens 输出 Token
     * @param costMicros 估算成本（micros）
     * @param cacheHits 缓存命中次数
     */
    public record ModelCost(
            String model,
            long requests,
            long inputTokens,
            long outputTokens,
            long costMicros,
            long cacheHits) {}
}
