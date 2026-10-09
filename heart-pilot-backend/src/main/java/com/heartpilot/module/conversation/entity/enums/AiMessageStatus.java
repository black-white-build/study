package com.heartpilot.module.conversation.entity.enums;

/** AI 消息生成状态枚举。 标记一条 assistant 消息在流式生成过程中所处的阶段，由对话服务在 SSE 流程中更新。 */
public enum AiMessageStatus {
    /** 正在流式生成中，内容尚未完整 */
    STREAMING,
    /** 生成成功完成，内容完整可用 */
    COMPLETED,
    /** 生成失败，errorMessage 记录原因 */
    FAILED,
    /** 用户主动停止了生成 */
    CANCELLED
}
