package com.heartpilot.module.agent.runtime;

import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.chat.messages.Message;

/**
 * ReAct 执行模板（抽象基类）。
 * ReAct = Reasoning（思考）+ Acting（行动）：循环驱动大模型先思考、再决定是否调用工具，
 * 直到模型认为已有足够信息给出最终答案，或达到最大步数上限（安全护栏）。
 * Stateless ReAct execution template. Every invocation owns its message history, so the Spring
 * singleton is safe.
 *
 * 子类需实现三个抽象方法：initialHistory（构造初始消息）、think（一轮思考，返回是否结束）、
 * act（执行工具调用，返回更新后的对话历史与观察摘要）。
 */
public abstract class ReActAgent {
    /** 最大推理步数，超过后强制结束并基于已有观察给出兜底结论 */
    private final int maxSteps;

    /**
     * @param maxSteps 最大思考-行动循环步数，防止工具调用无限循环
     */
    protected ReActAgent(int maxSteps) {
        this.maxSteps = maxSteps;
    }

    /**
     * 执行一次完整的 ReAct 循环。
     * 流程：构造初始历史 → 循环 [think 思考 → 若完成则返回，否则 act 执行工具并把观察追加到历史]。
     *
     * @param userPrompt 用户任务提示词
     * @return 执行结果（最终答案、观察列表、实际步数、是否正常完成）
     * @throws IllegalArgumentException 提示词为空时抛出
     */
    public AgentResult run(String userPrompt) {
        if (userPrompt == null || userPrompt.isBlank()) {
            throw new IllegalArgumentException("Agent prompt must not be blank");
        }
        // 每次调用独立持有消息历史，保证单例线程安全
        List<Message> history = new ArrayList<>(initialHistory(userPrompt));
        List<String> observations = new ArrayList<>();
        for (int step = 1; step <= maxSteps; step++) {
            Thought thought = think(history);
            // 模型本轮未发起工具调用，视为已得出最终答案
            if (thought.finished()) {
                return new AgentResult(thought.answer(), List.copyOf(observations), step, true);
            }
            // 执行工具调用，把工具返回结果并入下一轮对话历史
            Observation observation = act(history, thought);
            history = new ArrayList<>(observation.history());
            observations.add(observation.summary());
        }
        // 达到安全步数上限仍未收尾，用兜底文案结束，避免无限循环
        return new AgentResult(
                "已达到安全步骤上限，请基于现有检索结果继续规划。", List.copyOf(observations), maxSteps, false);
    }

    /** 构造初始对话历史（通常为一条用户消息） */
    protected abstract List<Message> initialHistory(String userPrompt);

    /**
     * 一轮思考：调用大模型，判断是否还需要继续调用工具。
     * @param history 当前完整对话历史
     * @return 思考结果（原始模型响应、是否结束、结束时的答案文本）
     */
    protected abstract Thought think(List<Message> history);

    /**
     * 一轮行动：根据上一轮思考中模型发起的工具调用，执行工具并把结果回填对话。
     * @param history 当前对话历史
     * @param thought 上一轮的思考结果
     * @return 行动结果（追加工具响应后的新历史、供日志/展示的观察摘要）
     */
    protected abstract Observation act(List<Message> history, Thought thought);

    /** 一轮思考的结果记录：原始模型响应、是否已结束、结束时的最终答案 */
    protected record Thought(Object modelResponse, boolean finished, String answer) {}

    /** 一轮行动的结果记录：更新后的对话历史、观察摘要文本 */
    protected record Observation(List<Message> history, String summary) {}

    /**
     * Agent 最终执行结果。
     * @param answer 最终答案文本
     * @param observations 每轮工具调用的观察摘要（用于溯源展示）
     * @param steps 实际执行的步数
     * @param completed true=模型正常得出结论；false=达到步数上限被强制结束
     */
    public record AgentResult(
            String answer, List<String> observations, int steps, boolean completed) {
        /** 将答案与工具观察拼接为可直接喂给下游或展示的文本 */
        public String formatted() {
            String evidence =
                    observations.isEmpty()
                            ? ""
                            : "\n\n工具观察：\n- " + String.join("\n- ", observations);
            return answer + evidence;
        }
    }
}
