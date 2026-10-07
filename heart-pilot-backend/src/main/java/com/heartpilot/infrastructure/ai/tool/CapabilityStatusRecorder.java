package com.heartpilot.infrastructure.ai.tool;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/**
 * 外部能力（AI 对话、网页搜索）的运行时状态记录器。
 * 各工具/服务在实际调用中发现"未配置 key""额度耗尽""鉴权失败"时更新这里，
 * 前端通过能力状态接口读取，把"额度不足"可视化到任务页，而不是静默降级成空结果。
 */
@Component
public class CapabilityStatusRecorder {

    /** 能力状态枚举：正常 / 未配置key / 额度耗尽 / 鉴权失败 / 临时错误 */
    public enum Status {
        OK,
        NO_KEY,
        QUOTA_EXHAUSTED,
        AUTH_FAILED,
        ERROR
    }

    private final AtomicReference<Map<String, Object>> webSearch =
            new AtomicReference<>(Map.of("status", Status.NO_KEY, "detail", ""));
    private final AtomicReference<Map<String, Object>> aiChat =
            new AtomicReference<>(Map.of("status", Status.NO_KEY, "detail", ""));

    /** 更新网页搜索能力状态 */
    public void recordWebSearch(Status status, String detail) {
        webSearch.set(
                Map.of(
                        "status", status,
                        "detail", detail == null ? "" : detail,
                        "updatedAt", Instant.now().toString()));
    }

    /** 更新 AI 对话能力状态 */
    public void recordAiChat(Status status, String detail) {
        aiChat.set(
                Map.of(
                        "status", status,
                        "detail", detail == null ? "" : detail,
                        "updatedAt", Instant.now().toString()));
    }

    /** 供接口输出：当前两项能力的状态快照 */
    public Map<String, Object> snapshot() {
        return Map.of("webSearch", webSearch.get(), "aiChat", aiChat.get());
    }
}
