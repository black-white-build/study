package com.heartpilot.module.usage.service.impl;

import com.heartpilot.module.conversation.entity.AiMessage;
import com.heartpilot.module.conversation.repository.MessageRepository;
import com.heartpilot.module.usage.dto.UsageDtos;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class UsageCostService {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private final MessageRepository messages;

    public UsageCostService(MessageRepository messages) {
        this.messages = messages;
    }

    public UsageDtos.CostDashboardResponse dashboard(Long userId, int requestedDays) {
        int days = Math.max(1, Math.min(requestedDays, 365));
        Instant end = Instant.now();
        Instant start = end.minus(days, ChronoUnit.DAYS);
        List<AiMessage> assistantMessages =
                messages
                        .findByUserIdAndCreatedAtBetweenOrderByCreatedAtAsc(userId, start, end)
                        .stream()
                        .filter(message -> "ASSISTANT".equals(message.getRole()))
                        .toList();

        Map<LocalDate, MutableCost> daily = new LinkedHashMap<>();
        Map<String, MutableCost> models = new LinkedHashMap<>();
        for (AiMessage message : assistantMessages) {
            LocalDate date = message.getCreatedAt().atZone(ZONE).toLocalDate();
            accumulate(daily.computeIfAbsent(date, ignored -> new MutableCost()), message);
            accumulate(
                    models.computeIfAbsent(
                            safeModel(message.getModel()), ignored -> new MutableCost()),
                    message);
        }
        MutableCost total = new MutableCost();
        assistantMessages.forEach(message -> accumulate(total, message));
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

    private void accumulate(MutableCost target, AiMessage message) {
        target.requests++;
        target.inputTokens += message.getInputTokens();
        target.outputTokens += message.getOutputTokens();
        target.costMicros += message.getEstimatedCostMicros();
        target.cacheSavedCostMicros += message.getCacheSavedCostMicros();
        if (message.isCacheHit()) target.cacheHits++;
    }

    private String safeModel(String model) {
        return model == null || model.isBlank() ? "unknown" : model;
    }

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
