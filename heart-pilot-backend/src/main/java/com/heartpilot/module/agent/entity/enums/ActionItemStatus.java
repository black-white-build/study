package com.heartpilot.module.agent.entity.enums;

/**
 * 行动条目（PlanActionItem）的完成状态。
 * 用户按条目勾选完成情况，"我的计划"页面据此展示逐条进度。
 */
public enum ActionItemStatus {
    /** 待执行 */
    PENDING,
    /** 已完成 */
    COMPLETED,
    /** 已跳过（用户决定不执行） */
    SKIPPED;
}
