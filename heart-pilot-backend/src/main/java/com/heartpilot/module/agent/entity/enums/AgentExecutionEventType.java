package com.heartpilot.module.agent.entity.enums;

/**
 * 执行事件类型枚举，对应 ReAct（推理-行动）范式中的各类事件。
 */
public enum AgentExecutionEventType {
    /** 思考：Agent 的推理过程 */
    THOUGHT,
    /** 行动：决定调用某个工具/能力 */
    ACTION,
    /** 观察：工具调用后的返回结果 */
    OBSERVATION,
    /** 结果：阶段性产出结论 */
    RESULT,
    /** 告警：非致命的异常提示（如部分数据缺失） */
    WARNING,
    /** 错误：执行出错 */
    ERROR
}
