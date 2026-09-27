package com.heartpilot.module.agent.entity.enums;

/**
 * 任务步骤状态枚举，标识单个执行步骤的生命周期状态。
 */
public enum AgentTaskStepStatus {
    /** 待执行 */
    PENDING,
    /** 正在执行 */
    RUNNING,
    /** 等待用户确认后继续 */
    WAITING_CONFIRMATION,
    /** 已完成 */
    COMPLETED,
    /** 执行失败 */
    FAILED,
    /** 已取消 */
    CANCELLED
}
