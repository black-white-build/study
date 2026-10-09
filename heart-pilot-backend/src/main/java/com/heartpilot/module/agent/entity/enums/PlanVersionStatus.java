package com.heartpilot.module.agent.entity.enums;

/**
 * 计划版本（PlanVersion）状态。 每次重新规划产生一个 DRAFT 版本；用户确认后置为 APPROVED（正式计划）； 用户驳回时当前草稿置为
 * REJECTED，随后产生的版本保留历史。
 */
public enum PlanVersionStatus {
    /** 草稿：生成中或等待用户确认 */
    DRAFT,
    /** 已确认：用户确认的正式计划版本 */
    APPROVED,
    /** 已取代：确认后又被新一轮重规划取代（保留历史） */
    SUPERSEDED,
    /** 已驳回：用户明确驳回的候选版本 */
    REJECTED;
}
