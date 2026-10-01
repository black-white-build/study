package com.heartpilot.module.agent.runtime;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;

/**
 * 基于 Spring AI Tool Calling 的 ReAct Agent 具体实现。
 * 与 ReActAgent 抽象基类配合：think 阶段让 DashScope 大模型在关闭内部自动执行的前提下输出工具调用请求；
 * act 阶段由本地 ToolCallingManager 显式执行工具，把结果回填对话历史，进入下一轮思考，
 * 直到模型不再发起工具调用即视为得出最终答案。
 * 关闭内部工具执行（internalToolExecutionEnabled=false）是关键：让工具调用完全由本类控制，
 * 以便记录每轮观察摘要并受 maxSteps 约束。
 */
public class ToolCallAgent extends ReActAgent {
    /** 聊天客户端：封装 DashScope 模型调用，think 阶段通过它发起对话 */
    private final ChatClient chatClient;
    /** 系统提示词：定义角色、工具边界与输出要求 */
    private final String systemPrompt;
    /** 允许本 Agent 调用的工具回调列表，过滤掉 null 元素 */
    private final List<ToolCallback> tools;
    /** 工具调用管理器：负责解析模型发起的工具调用并实际执行 */
    private final ToolCallingManager toolManager = ToolCallingManager.builder().build();
    /** 聊天选项：关闭 DashScope 内部自动工具执行，改由本类手动驱动 ReAct 循环 */
    private final ChatOptions chatOptions =
            DashScopeChatOptions.builder().withInternalToolExecutionEnabled(false).build();

    /**
     * @param chatClient 聊天客户端
     * @param systemPrompt 系统提示词
     * @param tools 可用工具回调数组（null 元素会被过滤）
     * @param maxSteps 最大 ReAct 步数
     */
    protected ToolCallAgent(
            ChatClient chatClient, String systemPrompt, ToolCallback[] tools, int maxSteps) {
        super(maxSteps);
        this.chatClient = chatClient;
        this.systemPrompt = systemPrompt;
        // 过滤 null，避免工具数组中混入空引用导致执行异常
        this.tools = Arrays.stream(tools).filter(Objects::nonNull).toList();
    }

    /**
     * 实现：初始历史仅包含一条 UserMessage，系统提示词在 think 阶段通过 .system(systemPrompt) 注入。
     */
    @Override
    protected List<Message> initialHistory(String userPrompt) {
        return List.of(new UserMessage(userPrompt));
    }

    /**
     * think：调用大模型。若响应中没有工具调用，说明模型已给出最终答案。
     */
    @Override
    protected Thought think(List<Message> history) {
        Prompt prompt = new Prompt(history, chatOptions);
        ChatResponse response =
                chatClient
                        .prompt(prompt)
                        .system(systemPrompt)
                        .toolCallbacks(tools)
                        .call()
                        .chatResponse();
        AssistantMessage assistant = response.getResult().getOutput();
        // 没有工具调用 = 模型认为信息已足够，进入收尾
        boolean finished = assistant.getToolCalls().isEmpty();
        return new Thought(
                response, finished, assistant.getText() == null ? "" : assistant.getText());
    }

    /**
     * act：执行模型本轮请求的工具调用，汇总各工具返回作为观察摘要。
     */
    @Override
    protected Observation act(List<Message> history, Thought thought) {
        ChatResponse response = (ChatResponse) thought.modelResponse();
        // 显式执行工具，得到包含 ToolResponseMessage 的新对话历史
        ToolExecutionResult result =
                toolManager.executeToolCalls(new Prompt(history, chatOptions), response);
        // 取出最后一条 ToolResponseMessage（工具结果汇总）
        ToolResponseMessage toolResponse =
                result.conversationHistory().stream()
                        .filter(ToolResponseMessage.class::isInstance)
                        .map(ToolResponseMessage.class::cast)
                        .reduce((first, second) -> second)
                        .orElseThrow();
        // 逐工具拼接"工具名：截断后的返回"，每条观察限制 800 字避免上下文膨胀
        String summary =
                toolResponse.getResponses().stream()
                        .map(
                                item ->
                                        item.name()
                                                + "："
                                                + shorten(String.valueOf(item.responseData()), 800))
                        .collect(Collectors.joining("；"));
        return new Observation(result.conversationHistory(), summary);
    }

    /** 截断字符串到指定长度，防止工具返回过长撑爆对话上下文 */
    private String shorten(String value, int max) {
        return value.substring(0, Math.min(max, value.length()));
    }
}
