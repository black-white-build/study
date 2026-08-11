package com.heartpilot.module.usage.controller;

import com.heartpilot.module.usage.dto.UsageDtos;
import com.heartpilot.module.usage.service.UsageCostService;
import com.heartpilot.security.CurrentUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/usage")
public class UsageController {
    private final UsageCostService service;
    private final CurrentUser current;

    public UsageController(UsageCostService service, CurrentUser current) {
        this.service = service;
        this.current = current;
    }

    @GetMapping("/cost-dashboard")
    UsageDtos.CostDashboardResponse dashboard(@RequestParam(defaultValue = "30") int days) {
        return service.dashboard(current.id(), days);
    }
}
