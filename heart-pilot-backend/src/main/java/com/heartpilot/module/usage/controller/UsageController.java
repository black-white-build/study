package com.heartpilot.module.usage.controller;

import com.heartpilot.module.usage.dto.UsageDtos;
import com.heartpilot.module.usage.service.UsageCostService;
import com.heartpilot.security.CurrentUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 用量与成本统计 Controller，路径前缀 /usage。 面向当前登录用户提供 AI 调用用量、Token 消耗与估算成本的看板数据。 */
@RestController
@RequestMapping("/usage")
public class UsageController {
    private final UsageCostService service;
    private final CurrentUser current;

    public UsageController(UsageCostService service, CurrentUser current) {
        this.service = service;
        this.current = current;
    }

    /** GET /usage/cost-dashboard?days=N —— 查询近 N 天（默认 30 天）的用量成本看板。 返回汇总指标、按日趋势和按模型分组的成本明细。 */
    @GetMapping("/cost-dashboard")
    UsageDtos.CostDashboardResponse dashboard(@RequestParam(defaultValue = "30") int days) {
        return service.dashboard(current.id(), days);
    }
}
