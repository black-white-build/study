package com.heartpilot.agent;

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

public class ToolCallAgent extends ReActAgent {
    private final ChatClient chatClient;
    private final String systemPrompt;
    private final List<ToolCallback> tools;
    private final ToolCallingManager toolManager = ToolCallingManager.builder().build();
    private final ChatOptions chatOptions =
            DashScopeChatOptions.builder().withInternalToolExecutionEnabled(false).build();

    protected ToolCallAgent(
            ChatClient chatClient, String systemPrompt, ToolCallback[] tools, int maxSteps) {
        super(maxSteps);
        this.chatClient = chatClient;
        this.systemPrompt = systemPrompt;
        this.tools = Arrays.stream(tools).filter(Objects::nonNull).toList();
    }

    @Override
    protected List<Message> initialHistory(String userPrompt) {
        return List.of(new UserMessage(userPrompt));
    }

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
        boolean finished = assistant.getToolCalls().isEmpty();
        return new Thought(
                response, finished, assistant.getText() == null ? "" : assistant.getText());
    }

    @Override
    protected Observation act(List<Message> history, Thought thought) {
        ChatResponse response = (ChatResponse) thought.modelResponse();
        ToolExecutionResult result =
                toolManager.executeToolCalls(new Prompt(history, chatOptions), response);
        ToolResponseMessage toolResponse =
                result.conversationHistory().stream()
                        .filter(ToolResponseMessage.class::isInstance)
                        .map(ToolResponseMessage.class::cast)
                        .reduce((first, second) -> second)
                        .orElseThrow();
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

    private String shorten(String value, int max) {
        return value.substring(0, Math.min(max, value.length()));
    }
}
