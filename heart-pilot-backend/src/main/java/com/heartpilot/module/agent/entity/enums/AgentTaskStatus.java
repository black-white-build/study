package com.heartpilot.module.agent.entity.enums;

import java.util.EnumSet;
import java.util.Set;

/**
 * Agent 任务状态枚举，定义任务的状态机。
 * 通过 allowedTargets 限制合法的状态流转，禁止非法跳转；
 * 终态（SUCCEEDED/FAILED/CANCELLED）外的状态可按规则流转或重试。
 */
public enum AgentTaskStatus {
    /** 已创建待运行 */
    WAITING,
    /** 正在执行 */
    RUNNING,
    /** 等待用户确认候选计划 */
    AWAITING_CONFIRMATION,
    /** 需求检查点：结构化需求存在冲突，等待用户调整约束（Tier2 Human-in-the-Loop） */
    AWAITING_REQUIREMENT,
    /** 等待重试（指数退避中） */
    RETRY_WAIT,
    /** 成功完成（终态） */
    SUCCEEDED,
    /** 失败（终态，但可恢复重试） */
    FAILED,
    /** 已取消（终态，可重新排队） */
    CANCELLED;

    /**
     * 判断当前状态是否允许流转到目标状态。
     * @param target 目标状态
     * @return 允许流转返回 true
     */
    public boolean canTransitionTo(AgentTaskStatus target) {
        return allowedTargets().contains(target);
    }

    /**
     * 是否为终态（终态不再由执行循环自动推进）。
     */
    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED;
    }

    /**
     * 定义每个状态允许流转到的目标状态集合，集中管理状态机规则。
     */
    private Set<AgentTaskStatus> allowedTargets() {
        return switch (this) {
            case WAITING -> EnumSet.of(RUNNING, AWAITING_REQUIREMENT, CANCELLED);
            case RUNNING ->
                    EnumSet.of(AWAITING_CONFIRMATION, AWAITING_REQUIREMENT, RETRY_WAIT, SUCCEEDED, FAILED, CANCELLED);
            case AWAITING_CONFIRMATION -> EnumSet.of(WAITING, RUNNING, CANCELLED);
            // 需求冲突待调整：用户修改约束后可重新执行（RUNNING），或取消/放弃
            case AWAITING_REQUIREMENT -> EnumSet.of(RUNNING, CANCELLED, FAILED);
            case RETRY_WAIT -> EnumSet.of(WAITING, RUNNING, FAILED, CANCELLED);
            case FAILED -> EnumSet.of(WAITING, RUNNING, CANCELLED);
            // 成功为终态，无任何后继状态
            case SUCCEEDED -> EnumSet.noneOf(AgentTaskStatus.class);
            // 取消后允许重新排队运行
            case CANCELLED -> EnumSet.of(WAITING);
        };
    }
}
