package com.heartpilot.module.agent.entity.enums;

/**
 * 行动的执行方式。
 * 与目标类型（GoalType）正交：执行方式回答"用什么动作实现"，目标类型回答"想达到什么"。
 * 例如"吵架后的咖啡店沟通" = PLACE_VISIT（执行方式）+ REPAIR（行动目标）。
 */
public enum ExecutionKind {
    /** 地点型：约定在某个地点进行见面/活动 */
    PLACE_VISIT,
    /** 消息型：发送一条文字消息，保存草稿、语气与发送时机 */
    MESSAGE,
    /** 沟通型：一次当面/语音沟通，保存开场白、关键表达与退出条件 */
    CONVERSATION,
    /** 表达型：准备一份礼物或仪式性表达 */
    GIFT_RITUAL,
    /** 自我练习型：面向自己的练习（复盘、情绪记录等） */
    SELF_PRACTICE,
    /** 观察型：观察记录自己的反应或情境事实，禁止推断他人 */
    OBSERVATION;

    /** 中文展示名，用于预览文本、执行轨迹与前端标签 */
    public String label() {
        return switch (this) {
            case PLACE_VISIT -> "地点行动";
            case MESSAGE -> "消息行动";
            case CONVERSATION -> "沟通行动";
            case GIFT_RITUAL -> "表达行动";
            case SELF_PRACTICE -> "自我练习";
            case OBSERVATION -> "观察行动";
        };
    }
}
