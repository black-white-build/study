package com.heartpilot.common.health.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 健康检查接口，路径前缀 /health。
 * 供容器编排（Docker/K8s）或负载均衡探活使用，不依赖数据库等内部组件，仅确认进程存活。
 */
@RestController
@RequestMapping("/health")
public class HealthController {

    /**
     * GET /health —— 存活探针，进程正常时固定返回 "ok"。
     */
    @GetMapping
    public String healthCheck() {
        return "ok";
    }
}
