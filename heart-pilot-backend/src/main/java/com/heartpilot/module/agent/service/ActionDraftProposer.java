package com.heartpilot.module.agent.service;

import com.heartpilot.module.agent.entity.enums.GoalType;
import com.heartpilot.module.agent.service.ActionEnricher.ActionDraft;
import java.util.List;

/**
 * 行动草案提议器（P6/P7）。
 * 基于用户目标与约束，识别计划的目标类型（GoalType），并提议一组
 * 多类型的行动草案（执行方式 + 标题 + 指令），后续由 ActionEnrichmentService
 * 按类型分派给对应富化器填充结构化数据。
 */
public interface ActionDraftProposer {
    /**
     * 分析结果：计划目标类型 + 行动草案列表。
     * @param goalType 计划整体的行动目标（可能为 null，表示未能识别）
     * @param drafts 提议的行动草案列表
     * @param aiGenerated true=由大模型生成；false=规则降级
     */
    record ActionProposal(GoalType goalType, List<ActionDraft> drafts, boolean aiGenerated) {}

    /** 提议行动计划草案 */
    ActionProposal propose(PlanningContext context);
}
