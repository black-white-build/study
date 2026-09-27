package com.heartpilot.module.usage.service;

import com.heartpilot.module.usage.dto.UsageDtos;

/**
 * 用量与成本统计服务接口。
 * 基于已落库的 AI 消息记录，聚合 Token 消耗与估算成本，供成本看板展示。
 */
public interface UsageCostService {
    /**
     * 生成指定用户近 requestedDays 天的成本看板数据。
     * @param userId 用户 ID
     * @param requestedDays 统计天数（会被限制在 1~365 之间）
     * @return 成本看板响应
     */
    UsageDtos.CostDashboardResponse dashboard(Long userId, int requestedDays);
}
