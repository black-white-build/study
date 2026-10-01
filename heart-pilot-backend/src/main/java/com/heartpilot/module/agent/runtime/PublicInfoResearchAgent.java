package com.heartpilot.module.agent.runtime;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * 公开信息研究 Agent（Public Info Research Agent）。 继承自 ToolCallAgent，采用 ReAct（Reasoning +
 * Acting）模式：由大模型自主决策调用搜索、地图、图片等 MCP 工具， 逐步核验城市公开信息（地点、路线、营业状态等），证据充足后停止工具调用并输出结论。
 * 系统提示词限定工具白名单（禁止终端/文件写入/资源下载），并要求所有动态事实保留来源、不得编造。 作为 Spring 单例，每次调用持有独立消息历史，线程安全。
 */
@Component
public class PublicInfoResearchAgent extends ToolCallAgent {
    /** 系统提示词：定义 Agent 角色、可用工具边界、证据留存与隐私约束 */
    private static final String SYSTEM_PROMPT =
            """
            你是“心旅”行动研究智能体，只负责为关系改善行动核验公开信息。
            使用搜索、地图或图片 MCP 工具前先判断必要性；不得使用终端、任意文件写入或资源下载。
            地点、路线、营业状态等动态信息必须保留来源；工具没有返回的事实不得编造。
            只使用当前规划任务明确提交的目标和约束，不得把无关隐私字段发送到外部搜索工具。
            得到足够证据后停止调用工具，输出精简的候选信息、来源和仍需人工确认的内容。
            """;

    /**
     * 构造器：注入安全工具白名单与 DashScope 聊天模型。 最大推理步数固定为 5 步，防止工具调用循环失控。
     *
     * @param tools 经 @Qualifier("safeAgentTools") 限定的安全工具回调数组
     * @param dashscopeChatModel 通义千问聊天模型
     */
    public PublicInfoResearchAgent(
            @Qualifier("safeAgentTools") ToolCallback[] tools, ChatModel dashscopeChatModel) {
        super(ChatClient.builder(dashscopeChatModel).build(), SYSTEM_PROMPT, tools, 5);
    }

    /**
     * 执行一次公开信息研究。
     *
     * @param publicResearchPrompt 研究任务提示词（含城市、需求、已选地点等上下文）
     * @return 大模型结论 + 工具观察记录拼接后的格式化文本
     */
    public String research(String publicResearchPrompt) {
        return run(publicResearchPrompt).formatted();
    }
}
