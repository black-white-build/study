package com.heartpilot.agent;

import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.chat.messages.Message;

/**
 * Stateless ReAct execution template. Every invocation owns its message history, so the Spring
 * singleton is safe.
 */
public abstract class ReActAgent {
    private final int maxSteps;

    protected ReActAgent(int maxSteps) {
        this.maxSteps = maxSteps;
    }

    public AgentResult run(String userPrompt) {
        if (userPrompt == null || userPrompt.isBlank()) {
            throw new IllegalArgumentException("Agent prompt must not be blank");
        }
        List<Message> history = new ArrayList<>(initialHistory(userPrompt));
        List<String> observations = new ArrayList<>();
        for (int step = 1; step <= maxSteps; step++) {
            Thought thought = think(history);
            if (thought.finished()) {
                return new AgentResult(thought.answer(), List.copyOf(observations), step, true);
            }
            Observation observation = act(history, thought);
            history = new ArrayList<>(observation.history());
            observations.add(observation.summary());
        }
        return new AgentResult(
                "已达到安全步骤上限，请基于现有检索结果继续规划。", List.copyOf(observations), maxSteps, false);
    }

    protected abstract List<Message> initialHistory(String userPrompt);

    protected abstract Thought think(List<Message> history);

    protected abstract Observation act(List<Message> history, Thought thought);

    protected record Thought(Object modelResponse, boolean finished, String answer) {}

    protected record Observation(List<Message> history, String summary) {}

    public record AgentResult(
            String answer, List<String> observations, int steps, boolean completed) {
        public String formatted() {
            String evidence =
                    observations.isEmpty()
                            ? ""
                            : "\n\n工具观察：\n- " + String.join("\n- ", observations);
            return answer + evidence;
        }
    }
}
