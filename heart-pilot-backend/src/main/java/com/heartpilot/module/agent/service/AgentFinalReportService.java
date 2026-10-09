package com.heartpilot.module.agent.service;

import com.heartpilot.module.agent.entity.AgentTask;
import java.util.List;

/** Agent 最终报告生成服务。 在用户确认候选计划后，基于实时检索到的行程证据，调用大模型生成最终行动报告 （含逐问题解答、地点与路线建议、预算说明等）。 */
public interface AgentFinalReportService {
    /**
     * 生成最终行动报告。
     *
     * @param task 任务实体（含用户目标、历史修订等上下文）
     * @param allRequirements 合并后的完整检索需求文本
     * @param questions 需要逐项回答的问题列表
     * @param budget 预算描述文本
     * @param note 用户补充说明
     * @param journey 实时行程检索证据（地点、路线等）
     * @return 最终报告文本（Markdown/结构化文本）
     */
    String generate(
            AgentTask task,
            String allRequirements,
            List<String> questions,
            String budget,
            String note,
            AgentJourneyResearchService.JourneyResearch journey);
}
