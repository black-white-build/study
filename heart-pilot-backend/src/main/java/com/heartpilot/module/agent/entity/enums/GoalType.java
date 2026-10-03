package com.heartpilot.module.agent.entity.enums;

/**
 * 行动/计划的目标类型。
 * 回答"这个计划想达到什么"，与执行方式（ExecutionKind）正交。
 */
public enum GoalType {
    /** 加深/维系连接 */
    CONNECTION,
    /** 修复关系裂痕 */
    REPAIR,
    /** 设定并维护边界 */
    BOUNDARY,
    /** 庆祝/肯定 */
    CELEBRATION,
    /** 做出一个决定 */
    DECISION,
    /** 自我成长 */
    SELF_GROWTH;

    /** 中文展示名 */
    public String label() {
        return switch (this) {
            case CONNECTION -> "加深连接";
            case REPAIR -> "修复关系";
            case BOUNDARY -> "设定边界";
            case CELEBRATION -> "庆祝肯定";
            case DECISION -> "做出决定";
            case SELF_GROWTH -> "自我成长";
        };
    }
}
