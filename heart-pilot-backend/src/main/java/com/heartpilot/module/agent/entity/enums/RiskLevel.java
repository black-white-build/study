package com.heartpilot.module.agent.entity.enums;

/**
 * 行动条目的风险等级。
 * 由规划安全检查在草案生成阶段评估；高风险计划直接拒绝生成。
 */
public enum RiskLevel {
    /** 低风险 */
    LOW,
    /** 中风险：需要用户确认后执行 */
    MEDIUM,
    /** 高风险：默认拒绝 */
    HIGH;
}
