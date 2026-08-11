package com.heartpilot.infrastructure.ai;

import java.util.List;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

@Component
public class RelationshipAiClient {
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

    private final ChatClient client;

    public RelationshipAiClient(@Qualifier("dashscopeChatModel") ChatModel model) {
        client = ChatClient.builder(model).defaultSystem(SYSTEM_PROMPT).build();
    }

    public Flux<String> stream(String prompt) {
        return client.prompt().user(prompt).stream().content();
    }

    public RelationshipAnalysis analyze(String prompt) {
        return client.prompt()
                .system(SYSTEM_PROMPT + "\n请生成结构化关系分析报告，行动项 3 至 7 条，风险等级只能是低、中、高或紧急。")
                .user(prompt)
                .call()
                .entity(RelationshipAnalysis.class);
    }

    public record RelationshipAnalysis(
            String title,
            String problemSummary,
            String relationshipStatus,
            String conflictType,
            String riskLevel,
            String analysis,
            List<String> actions,
            String reviewAt) {}
}
