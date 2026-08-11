package com.heartpilot.module.usage.service;

import com.heartpilot.module.usage.dto.UsageDtos;

public interface UsageCostService {
    UsageDtos.CostDashboardResponse dashboard(Long userId, int requestedDays);
}
