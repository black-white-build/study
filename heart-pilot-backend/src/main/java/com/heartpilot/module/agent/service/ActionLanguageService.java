package com.heartpilot.module.agent.service;

import java.util.List;

/**
 * 行动文案生成服务（P7）。 为"不需要外部工具、只需要语言生成"的行动类型产出结构化文案： 消息草稿、沟通脚本、表达计划、自我练习、观察任务。 大模型不可用时自动降级为规则文案，保证规划流程不被
 * AI 故障阻断。
 */
public interface ActionLanguageService {
    /** 消息草稿：文本 + 语气 + 发送时机 + 禁用表达 */
    record MessageDraft(
            String text, String tone, String sendTiming, List<String> forbiddenExpressions) {}

    /**
     * 消息生成的用户指定选项：发送渠道 / 语气风格 / 对方回复期待 / 补充背景。 四个字段均可为空：为空表示用户未指定，由模型自由发挥。
     * 生成消息草稿时必须逐项遵守这些输入，消息要服务于计划目标并紧扣补充背景中的细节。
     */
    record MessageOptions(String channel, String toneStyle, String replyExpectation, String notes) {
        /** 全部未指定时的空选项 */
        static MessageOptions empty() {
            return new MessageOptions(null, null, null, null);
        }
    }

    /** 沟通脚本：目标、开场白、关键表达、具体请求、退出条件 */
    record ConversationScript(
            String goal,
            String opening,
            List<String> keyExpressions,
            String concreteRequest,
            String exitCondition) {}

    /** 表达计划（礼物/仪式）：准备事项、预算、步骤 + AI 分析用户输入后给出的具体礼物候选 */
    record GiftIdea(String title, String reason, String priceHint) {}

    /** 表达计划（礼物/仪式）：准备事项、预算、步骤、具体礼物候选 */
    record GiftPlan(
            String preparation, String budgetText, List<String> steps, List<GiftIdea> ideas) {}

    /**
     * 礼物搜索校准证据：一个搜索关键词 + 该关键词搜回的网页摘要（标题/摘要/链接）。 由 WebSearchService 产出，拼进 draftGift 的
     * prompt，用于校准价格区间与真实品牌名。
     */
    record GiftSearchEvidence(String keyword, List<WebSearchService.SearchResult> results) {}

    /**
     * 自我练习：方案名、练习内容、时长（分钟）、完成标准。 name 用于候选方案的标题展示（如"方案一：每周3次有氧燃脂计划"），
     * 必须引用用户输入的关键词（计划内容/期望效果），禁止套用与输入无关的固定方案名。
     */
    record PracticePlan(
            String name,
            String practiceContent,
            Integer durationMinutes,
            String completionCriteria) {}

    /** 观察任务：观察内容、记录字段、禁止推断事项 */
    record ObservationPlan(
            String observeContent, List<String> recordFields, String forbiddenInferences) {}

    /**
     * 生成一条消息草稿。
     *
     * @param goal 计划目标（含目标类型）
     * @param background 任务目标与约束描述
     * @param options 用户指定的发送渠道/语气风格/回复期待/补充背景，生成时必须逐项遵守
     */
    MessageDraft draftMessage(String goal, String background, MessageOptions options);

    /** 生成一次沟通的脚本 */
    ConversationScript draftConversation(String goal, String background);

    /**
     * 生成一份表达/礼物计划。
     *
     * @param searchEvidence 网页搜索校准证据（可为空：为空时不拼摘要段，保持原有纯 AI 行为）
     */
    GiftPlan draftGift(
            String goal, String background, String budget, List<GiftSearchEvidence> searchEvidence);

    /**
     * 生成一份自我练习计划（多候选方案中的一个）。
     *
     * @param goal 计划目标（含目标类型）
     * @param background 任务目标与约束描述
     * @param practiceNotes 用户填写的计划内容/期望效果/频率等（可能为空；非空时练习必须围绕其关键词展开）
     * @param variant 候选方案序号（从 1 开始），用于区分差异化思路（如循序渐进/效果打卡/场景融入）
     * @return 一份方案（方案名引用用户关键词）
     */
    PracticePlan draftPractice(String goal, String background, String practiceNotes, int variant);

    /** 生成一份观察任务（默认面向观察自己的反应与情境事实） */
    ObservationPlan draftObservation(String goal, String background);
}
