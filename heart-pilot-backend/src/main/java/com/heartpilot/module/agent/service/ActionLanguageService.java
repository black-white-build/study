package com.heartpilot.module.agent.service;

import java.util.List;

/**
 * 行动文案生成服务（P7）。
 * 为"不需要外部工具、只需要语言生成"的行动类型产出结构化文案：
 * 消息草稿、沟通脚本、表达计划、自我练习、观察任务。
 * 大模型不可用时自动降级为规则文案，保证规划流程不被 AI 故障阻断。
 */
public interface ActionLanguageService {
    /** 消息草稿：文本 + 语气 + 发送时机 + 禁用表达 */
    record MessageDraft(String text, String tone, String sendTiming, List<String> forbiddenExpressions) {}

    /** 沟通脚本：目标、开场白、关键表达、具体请求、退出条件 */
    record ConversationScript(
            String goal,
            String opening,
            List<String> keyExpressions,
            String concreteRequest,
            String exitCondition) {}

    /** 表达计划（礼物/仪式）：准备事项、预算、步骤 */
    record GiftPlan(String preparation, String budgetText, List<String> steps) {}

    /** 自我练习：练习内容、时长（分钟）、完成标准 */
    record PracticePlan(String practiceContent, Integer durationMinutes, String completionCriteria) {}

    /** 观察任务：观察内容、记录字段、禁止推断事项 */
    record ObservationPlan(String observeContent, List<String> recordFields, String forbiddenInferences) {}

    /** 生成一条消息草稿 */
    MessageDraft draftMessage(String goal, String background);

    /** 生成一次沟通的脚本 */
    ConversationScript draftConversation(String goal, String background);

    /** 生成一份表达/礼物计划 */
    GiftPlan draftGift(String goal, String background, String budget);

    /** 生成一份自我练习计划 */
    PracticePlan draftPractice(String goal, String background);

    /** 生成一份观察任务（默认面向观察自己的反应与情境事实） */
    ObservationPlan draftObservation(String goal, String background);
}
