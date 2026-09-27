package com.heartpilot.module.agent.entity.enums;

/**
 * 工具调用状态枚举，标识一次外部工具调用的结果状态。
 */
public enum ToolCallStatus {
    /** 调用进行中 */
    RUNNING,
    /** 调用成功 */
    SUCCEEDED,
    /** 调用失败 */
    FAILED,
    /** 调用超时 */
    TIMED_OUT,
    /** 调用被取消 */
    CANCELLED
}
