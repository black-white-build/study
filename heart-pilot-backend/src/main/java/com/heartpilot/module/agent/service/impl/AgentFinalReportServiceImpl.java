package com.heartpilot.module.agent.service.impl;

import com.heartpilot.infrastructure.ai.RelationshipAiClient;
import com.heartpilot.module.agent.entity.AgentTask;
import com.heartpilot.module.agent.entity.enums.AgentExecutionEventStatus;
import com.heartpilot.module.agent.entity.enums.AgentExecutionEventType;
import com.heartpilot.module.agent.entity.enums.AgentExecutionPhase;
import com.heartpilot.module.agent.service.AgentExecutionTraceService;
import com.heartpilot.module.agent.service.AgentFinalReportService;
import com.heartpilot.module.agent.service.AgentJourneyResearchService;
import com.heartpilot.module.agent.service.AgentTaskInputService;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Service;

/**
 * 基于已持久化、可核验的行程证据，生成最终面向用户的行动报告。 Generates the final user-facing report from persisted, verifiable
 * journey evidence.
 *
 * <p>可靠性设计要点： - 调用大模型流式生成（90 秒超时），一旦超时或异常，降级为已生成的计划预览 + 固定沟通/安全提示， 保证用户至少能拿到一份可用报告，而不是整个任务失败 -
 * 若模型输出遗漏了"可核验地点与路线"章节，主动把证据列表追加到报告末尾，防止编造 - 生成前后各写一条执行轨迹（RUNNING / SUCCEEDED），记录耗时与地点、路线数量
 */
@Service
public class AgentFinalReportServiceImpl implements AgentFinalReportService {
    /** 大模型客户端（DashScope），用于流式生成报告正文 */
    private final RelationshipAiClient ai;

    /** 任务输入参数与文案格式化工具 */
    private final AgentTaskInputService taskInput;

    /** 执行轨迹记录器，用于审计报告生成过程 */
    private final AgentExecutionTraceService executionTrace;

    /** 构造器注入 AI 客户端、输入服务与轨迹服务。 */
    public AgentFinalReportServiceImpl(
            RelationshipAiClient ai,
            AgentTaskInputService taskInput,
            AgentExecutionTraceService executionTrace) {
        this.ai = ai;
        this.taskInput = taskInput;
        this.executionTrace = executionTrace;
    }

    /**
     * 生成最终行动报告正文。 流程：构造 Prompt → 流式调用大模型（90 秒超时）→ 失败降级为计划预览 → 若模型遗漏证据章节则追加 → 写轨迹。
     *
     * @param task 任务实体（含 planPreview、版本号等）
     * @param allRequirements 汇总后的全部需求文本（目标 + 问题 + 修改 + 档案偏好）
     * @param questions 需要逐项回答的问题列表
     * @param budget 当前有效预算文本
     * @param note 用户确认时的补充说明
     * @param journey 已检索并确认的行程证据（地点、路线）
     * @return 最终报告 Markdown 文本
     */
    @Override
    public String generate(
            AgentTask task,
            String allRequirements,
            List<String> questions,
            String budget,
            String note,
            AgentJourneyResearchService.JourneyResearch journey) {
        String prompt = buildPrompt(task, allRequirements, questions, budget, note);
        // 记录生成开始（RUNNING）轨迹
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
            // 流式收集模型输出，整体阻塞等待，最长 90 秒
            content =
                    String.join("", ai.stream(prompt).collectList().block(Duration.ofSeconds(90)));
        } catch (Exception ignored) {
            // 模型不可用/超时：降级为计划预览 + 固定沟通与安全提示，保证任务不失败
            content =
                    task.getPlanPreview()
                            + "\n\n## 沟通提示\n- 提前确认双方时间和预算。"
                            + "\n- 行程中保留可以随时调整或结束的空间。"
                            + "\n\n## 安全提醒\n- 出发前再次核对营业时间、预约要求和实时路线。";
        }
        // 模型可能遗漏可核验地点章节，这里兜底追加证据，避免最终报告缺失真实来源
        if (!content.contains("## 可核验地点与路线")) {
            content += journey.evidence().formatted();
        }
        // 记录生成完成（SUCCEEDED）轨迹，附带耗时
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

    /**
     * 构造最终报告的大模型 Prompt。 用文本块（text block）编写严格的客服角色与输出约束，强调： 不得编造店名/距离/链接、按问题原顺序回答、预算以当前有效值为准。 用 %s
     * 占位注入预算、全部需求、问题列表、已确认资料和补充说明。
     */
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

    /** 记录一条报告生成阶段的执行轨迹（步骤 6）。 ACTION 类型记为 RUNNING，RESULT 类型记为 SUCCEEDED，provider 固定为 DashScope。 */
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
