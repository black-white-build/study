package com.heartpilot.module.agent.entity.enums;

/** 执行事件状态枚举，标识某条执行事件的当前进展。 */
public enum AgentExecutionEventStatus {
    /** 事件正在进行中 */
    RUNNING,
    /** 事件已成功完成 */
    SUCCEEDED,
    /** 事件执行失败 */
    FAILED
}
