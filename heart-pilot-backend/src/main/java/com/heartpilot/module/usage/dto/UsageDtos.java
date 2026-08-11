package com.heartpilot.module.usage.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class UsageDtos {
    private UsageDtos() {}

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

    public record DailyCost(
            LocalDate date,
            long requests,
            long inputTokens,
            long outputTokens,
            long costMicros,
            long cacheHits,
            long cacheSavedCostMicros) {}

    public record ModelCost(
            String model,
            long requests,
            long inputTokens,
            long outputTokens,
            long costMicros,
            long cacheHits) {}
}
