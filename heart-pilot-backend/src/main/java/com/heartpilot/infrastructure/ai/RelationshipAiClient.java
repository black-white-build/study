package com.heartpilot.infrastructure.ai;

import java.util.List;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * 关系成长 AI 客户端。
 * 封装对大模型（DashScope）的调用，内置"心旅关系顾问"系统提示词，
 * 对外提供两种能力：流式对话（stream）与结构化关系分析报告（analyze）。
 */
@Component
public class RelationshipAiClient {
    /**
     * 系统提示词：定义 AI 角色、回答风格、安全边界（不做精神疾病诊断、高风险情形引导专业援助）
     * 与输出格式约束（短段落、编号提问、分点建议、末尾"今天可以做的小行动"等）。
     */
    public static final String SYSTEM_PROMPT =
            """
            你是「心旅 HeartPilot」关系成长顾问。先共情和澄清事实，再给温和、具体、可执行的建议。
            不做精神疾病诊断，不鼓励操控、跟踪、威胁或伤害。遇到暴力、自伤、控制等高风险情形，优先建议联系可信任的人和当地专业援助。
            明确区分事实、推测和建议；若提供了知识片段，回答末尾必须用“参考知识：”列出实际采用的来源，不得编造来源。

            输出格式要求：
            - 使用短段落，每段只表达一个重点；
            - 需要用户补充信息时，使用编号问题；
            - 提供建议时，使用分点清单并给出优先顺序；
            - 最后单独列出一个“今天可以做的小行动”；
            - 避免连续堆砌长句，不使用空泛口号。
            """;

    /** 预置了系统提示词的对话客户端 */
    private final ChatClient client;

    /**
     * 基于 DashScope ChatModel 构建 ChatClient。
     *
     * @param model 通过 @Qualifier("dashscopeChatModel") 注入的通义千问对话模型
     */
    public RelationshipAiClient(@Qualifier("dashscopeChatModel") ChatModel model) {
        client = ChatClient.builder(model).defaultSystem(SYSTEM_PROMPT).build();
    }

    /**
     * 流式对话：按 token 实时返回文本片段。
     *
     * @param prompt 用户输入
     * @return SSE 式的文本流
     */
    public Flux<String> stream(String prompt) {
        return client.prompt().user(prompt).stream().content();
    }

    /**
     * 生成结构化关系分析报告。
     * 在系统提示词基础上追加结构化输出要求，由 Spring AI 的 entity() 把模型 JSON 反序列化为 record。
     *
     * @param prompt 用户输入
     * @return 结构化分析结果（标题、风险等级、行动项等）
     */
    public RelationshipAnalysis analyze(String prompt) {
        return client.prompt()
                .system(SYSTEM_PROMPT + "\n请生成结构化关系分析报告，行动项 3 至 7 条，风险等级只能是低、中、高或紧急。")
                .user(prompt)
                .call()
                .entity(RelationshipAnalysis.class);
    }

    /**
     * 结构化关系分析报告 record。
     */
    public record RelationshipAnalysis(
            /** 报告标题 */
            String title,
            /** 问题概述 */
            String problemSummary,
            /** 关系现状描述 */
            String relationshipStatus,
            /** 冲突类型 */
            String conflictType,
            /** 风险等级：低 / 中 / 高 / 紧急 */
            String riskLevel,
            /** 详细分析 */
            String analysis,
            /** 可执行行动项（3~7 条） */
            List<String> actions,
            /** 建议复盘时间 */
            String reviewAt) {}
}
