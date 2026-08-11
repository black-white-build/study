package com.heartpilot.module.agent.runtime;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class PublicInfoResearchAgent extends ToolCallAgent {
    private static final String SYSTEM_PROMPT =
            """
            你是“心旅”行动研究智能体，只负责为关系改善行动核验公开信息。
            使用搜索、地图或图片 MCP 工具前先判断必要性；不得使用终端、任意文件写入或资源下载。
            地点、路线、营业状态等动态信息必须保留来源；工具没有返回的事实不得编造。
            用户的关系档案只用于约束方案，不得把隐私字段发送到外部搜索工具。
            得到足够证据后停止调用工具，输出精简的候选信息、来源和仍需人工确认的内容。
            """;

    public PublicInfoResearchAgent(
            @Qualifier("safeAgentTools") ToolCallback[] tools, ChatModel dashscopeChatModel) {
        super(ChatClient.builder(dashscopeChatModel).build(), SYSTEM_PROMPT, tools, 5);
    }

    public String research(String publicResearchPrompt) {
        return run(publicResearchPrompt).formatted();
    }
}
