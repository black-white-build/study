package com.heartpilot.module.agent.entity.enums;

/** 行动计划（ActionPlan）整体状态。 计划聚合根的生命周期：草稿 → 已确认（有正式版本）。归档态预留。 */
public enum ActionPlanStatus {
    /** 草稿：已有候选版本但用户尚未确认 */
    DRAFT,
    /** 已确认：存在被用户确认的正式版本 */
    APPROVED,
    /** 已归档：被新计划取代或用户删除后保留历史 */
    ARCHIVED;
}
