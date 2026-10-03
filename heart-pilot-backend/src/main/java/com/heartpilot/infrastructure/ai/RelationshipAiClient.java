package com.heartpilot.infrastructure.ai;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/** AI 答疑客户端，只负责在固定系统边界内生成流式回答。 */
@Component
public class RelationshipAiClient {
    /** 系统提示词：定义 AI 角色、回答风格、安全边界（不做精神疾病诊断、高风险情形引导专业援助）。 */
    /** 预置了系统提示词的对话客户端 */
    private final ChatClient client;

    /**
     * 基于 DashScope ChatModel 构建 ChatClient。
     *
     * @param model 通过 @Qualifier("dashscopeChatModel") 注入的通义千问对话模型
     */
    public RelationshipAiClient(
            @Qualifier("dashscopeChatModel") ChatModel model, AnswerPromptBuilder prompts) {
        client = ChatClient.builder(model).defaultSystem(prompts.systemPrompt()).build();
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
}
