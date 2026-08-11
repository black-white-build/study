package com.heartpilot.module.agent.service.impl;

import com.heartpilot.infrastructure.ai.RelationshipAiClient;
import com.heartpilot.module.agent.entity.AgentTask;
import com.heartpilot.module.agent.entity.enums.AgentExecutionEventStatus;
import com.heartpilot.module.agent.entity.enums.AgentExecutionEventType;
import com.heartpilot.module.agent.entity.enums.AgentExecutionPhase;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Service;

/** Generates the final user-facing report from persisted, verifiable journey evidence. */
@Service
public class AgentFinalReportService {
    private final RelationshipAiClient ai;
    private final AgentTaskInputService taskInput;
    private final AgentExecutionTraceService executionTrace;

    public AgentFinalReportService(
            RelationshipAiClient ai,
            AgentTaskInputService taskInput,
            AgentExecutionTraceService executionTrace) {
        this.ai = ai;
        this.taskInput = taskInput;
        this.executionTrace = executionTrace;
    }

    public String generate(
            AgentTask task,
            String allRequirements,
            List<String> questions,
            String budget,
            String note,
            AgentJourneyResearchService.JourneyResearch journey) {
        String prompt = buildPrompt(task, allRequirements, questions, budget, note);
        trace(
                task,
                AgentExecutionEventType.ACTION,
                "正在生成带来源的最终行动计划",
                "模型将使用已保存的真实地点、地图链接、路线距离和预计耗时生成报告。",
                null,
                journey);
        long generationStarted = System.nanoTime();
        String content;
        try {
            content =
                    String.join("", ai.stream(prompt).collectList().block(Duration.ofSeconds(90)));
        } catch (Exception ignored) {
            content =
                    task.getPlanPreview()
                            + "\n\n## 沟通提示\n- 提前确认双方时间和预算。"
                            + "\n- 行程中保留可以随时调整或结束的空间。"
                            + "\n\n## 安全提醒\n- 出发前再次核对营业时间、预约要求和实时路线。";
        }
        if (!content.contains("## 可核验地点与路线")) {
            content += journey.evidence().formatted();
        }
        trace(
                task,
                AgentExecutionEventType.RESULT,
                "最终行动计划已生成",
                "报告已引用 "
                        + journey.evidence().places().size()
                        + " 个真实地点和 "
                        + journey.evidence().routes().size()
                        + " 段可核验路线。",
                elapsedMillis(generationStarted),
                journey);
        return content;
    }

    private String buildPrompt(
            AgentTask task,
            String allRequirements,
            List<String> questions,
            String budget,
            String note) {
        return """
                你是专业、自然、负责的行程咨询客服。请基于系统为用户检索并经用户确认的公开资料，
                生成一份可执行的本地行动报告。回答时要像你在主动为用户做攻略，而不是审阅用户提交的候选清单。

                输出必须使用清晰的中文结构：
                1. 只展示与用户目标和问题直接相关的部分；用户没有问到的类别不要为了凑结构而展示“缺少/没有”；
                2. 优先直接给出推荐结果，再自然说明选择理由、地址、路线、预算和来源链接，避免机械套用“结论/依据/建议”三段式；
                3. 系统已经按每个问题类别分别补充搜索。应综合全部分类结果主动回答，不要写“只在候选中找到”“若坚持只用候选则无解”；
                4. 主语必须准确：使用“我为你检索到”“本次检索结果显示”，不得说“你提供的候选”“你列出的条目”；
                5. 问题涉及超市、酒店、餐厅、交通等类别时，分别使用对应类别的来源，不要用别的类别数量推断该类别不存在；
                6. 对确实仍无可靠来源的问题，简短说明“本次暂未查到可核验信息”，并给出如何核验的具体动作；不得编造新的店名、距离、营业状态或链接；
                7. 地点资料明确时，按用户需要给出可执行顺序、分项预算和必要备选；没有相关需求时不要固定加入用餐点或活动点；
                8. 不得添加其他城市，不得用理论文章替代真实地点；未知信息使用“出发前请通过所附来源核验”；
                9. 若存在逐项问题，严格按原顺序完整回答，但行文保持客服式、自然、简洁；
                10. 不要输出面向系统流程的元说明，直接为用户呈现攻略和答案。
                11. 当前有效预算规则是：%s。它是最后一次修改后的唯一预算依据，优先于历史文字中的金额。

                用户全部要求（包含最初输入和历次修改）：
                %s

                需要逐项回答的问题：
                %s

                系统按类别检索并经用户确认的资料：
                %s

                用户确认补充：%s
                """
                .formatted(
                        taskInput.budgetLabel(budget),
                        allRequirements,
                        questions.isEmpty()
                                ? "无单独问题"
                                : String.join(
                                        "\n",
                                        questions.stream()
                                                .map(question -> "- " + question)
                                                .toList()),
                        task.getPlanPreview(),
                        note == null ? "无" : note);
    }

    private void trace(
            AgentTask task,
            AgentExecutionEventType type,
            String title,
            String detail,
            Long durationMs,
            AgentJourneyResearchService.JourneyResearch journey) {
        executionTrace.record(
                task.getId(),
                task.getVersionNo(),
                6,
                AgentExecutionPhase.GENERATE,
                type,
                type == AgentExecutionEventType.ACTION
                        ? AgentExecutionEventStatus.RUNNING
                        : AgentExecutionEventStatus.SUCCEEDED,
                title,
                detail,
                "DashScope",
                "chat-generation",
                journey.evidence().places().size(),
                durationMs,
                null,
                Map.of("routeCount", journey.evidence().routes().size()));
    }

    private long elapsedMillis(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }
}
