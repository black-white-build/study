package com.heartpilot.module.usage.service.impl;

import com.heartpilot.module.conversation.entity.AiMessage;
import com.heartpilot.module.conversation.repository.MessageRepository;
import com.heartpilot.module.usage.dto.UsageDtos;
import com.heartpilot.module.usage.service.UsageCostService;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 用量与成本统计服务实现。
 * 计费口径：不实时调用模型服务商账单，而是基于已落库的 ASSISTANT 消息记录做估算——
 * 每条 AI 回复消息在生成时已写入 inputTokens / outputTokens / estimatedCostMicros /
 * cacheSavedCostMicros / providerLatencyMs 等字段，这里按时间区间拉取后聚合。
 *
 * 聚合维度：
 * - 总体汇总：请求数、Token、成本、缓存命中与节省、平均耗时
 * - 按日趋势（东八区日期归属）
 * - 按模型分组
 * 金额单位统一为 micros（百万分之一元），避免浮点误差。
 */
@Service
public class UsageCostServiceImpl implements UsageCostService {
    /** 按东八区日期归属统计每日数据 */
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private final MessageRepository messages;

    public UsageCostServiceImpl(MessageRepository messages) {
        this.messages = messages;
    }

    /**
     * 统计近 N 天的成本看板。
     * 天数被截断到 [1, 365]；只统计 ASSISTANT 角色消息（即真正消耗 Token 的模型回复）。
     */
    @Override
    public UsageDtos.CostDashboardResponse dashboard(Long userId, int requestedDays) {
        // 限制查询天数范围，防止误传超大值拉全表
        int days = Math.max(1, Math.min(requestedDays, 365));
        Instant end = Instant.now();
        Instant start = end.minus(days, ChronoUnit.DAYS);
        // 拉取区间内该用户全部消息，再过滤出 ASSISTANT 回复
        List<AiMessage> assistantMessages =
                messages
                        .findByUserIdAndCreatedAtBetweenOrderByCreatedAtAsc(userId, start, end)
                        .stream()
                        .filter(message -> "ASSISTANT".equals(message.getRole()))
                        .toList();

        // 按日聚合（LinkedHashMap 保持时间正序）
        Map<LocalDate, MutableCost> daily = new LinkedHashMap<>();
        // 按模型名称聚合
        Map<String, MutableCost> models = new LinkedHashMap<>();
        for (AiMessage message : assistantMessages) {
            // 消息时间戳转东八区日期作为分组键
            LocalDate date = message.getCreatedAt().atZone(ZONE).toLocalDate();
            accumulate(daily.computeIfAbsent(date, ignored -> new MutableCost()), message);
            accumulate(
                    models.computeIfAbsent(
                            safeModel(message.getModel()), ignored -> new MutableCost()),
                    message);
        }
        // 总体汇总
        MutableCost total = new MutableCost();
        assistantMessages.forEach(message -> accumulate(total, message));
        // 平均模型耗时：仅统计有 latency 的消息
        long averageLatency =
                Math.round(
                        assistantMessages.stream()
                                .filter(message -> message.getProviderLatencyMs() != null)
                                .mapToLong(AiMessage::getProviderLatencyMs)
                                .average()
                                .orElse(0));

        return new UsageDtos.CostDashboardResponse(
                start,
                end,
                "CNY",
                "按消息估算；默认 qwen-plus 单价为输入 0.8 元/百万 Token、输出 2 元/百万 Token，可由环境变量覆盖。实际账单以模型服务商为准。",
                total.requests,
                total.inputTokens,
                total.outputTokens,
                total.costMicros,
                total.cacheHits,
                // 缓存命中率 = 缓存命中次数 / 总请求数，除零兜底为 0
                total.requests == 0 ? 0 : (double) total.cacheHits / total.requests,
                total.cacheSavedCostMicros,
                averageLatency,
                daily.entrySet().stream()
                        .map(entry -> entry.getValue().daily(entry.getKey()))
                        .toList(),
                models.entrySet().stream()
                        .map(entry -> entry.getValue().model(entry.getKey()))
                        .toList());
    }

    /** 将单条消息的用量字段累加到聚合容器中 */
    private void accumulate(MutableCost target, AiMessage message) {
        target.requests++;
        target.inputTokens += message.getInputTokens();
        target.outputTokens += message.getOutputTokens();
        target.costMicros += message.getEstimatedCostMicros();
        target.cacheSavedCostMicros += message.getCacheSavedCostMicros();
        if (message.isCacheHit()) target.cacheHits++;
    }

    /** 模型名为空时归到 unknown 桶，避免分组键为 null */
    private String safeModel(String model) {
        return model == null || model.isBlank() ? "unknown" : model;
    }

    /** 可变聚合容器，流式累加过程中使用，最后转换为不可变 DTO */
    private static final class MutableCost {
        private long requests;
        private long inputTokens;
        private long outputTokens;
        private long costMicros;
        private long cacheHits;
        private long cacheSavedCostMicros;

        private UsageDtos.DailyCost daily(LocalDate date) {
            return new UsageDtos.DailyCost(
                    date,
                    requests,
                    inputTokens,
                    outputTokens,
                    costMicros,
                    cacheHits,
                    cacheSavedCostMicros);
        }

        private UsageDtos.ModelCost model(String model) {
            return new UsageDtos.ModelCost(
                    model, requests, inputTokens, outputTokens, costMicros, cacheHits);
        }
    }
}
