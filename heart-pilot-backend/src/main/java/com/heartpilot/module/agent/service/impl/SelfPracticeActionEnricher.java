package com.heartpilot.module.agent.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.heartpilot.module.agent.entity.PlanActionItem;
import com.heartpilot.module.agent.entity.enums.ExecutionKind;
import com.heartpilot.module.agent.service.ActionLanguageService;
import com.heartpilot.module.agent.service.PlanningContext;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 自我练习型行动富化器。 产出面向自己的练习内容、建议时长与完成标准；不要求改变对方，不诊断他人。
 *
 * <p>多候选方案设计（参考送礼多候选思路）：草案提议器为自我练习生成多套候选草案 （hints.variant = 1..N），本富化器按 variant 向语言服务请求差异化方案；
 * 用户填写的计划内容/期望效果/频率（contextNotes）与历史修改要求（revisions） 会一并传入提示词，确保练习内容围绕用户关键词展开，不再套用固定的情绪复盘模板。
 */
@Service
public class SelfPracticeActionEnricher extends AbstractActionEnricher {
    private final ActionLanguageService language;

    public SelfPracticeActionEnricher(ActionLanguageService language, ObjectMapper json) {
        super(json);
        this.language = language;
    }

    @Override
    public boolean supports(ExecutionKind kind) {
        return kind == ExecutionKind.SELF_PRACTICE;
    }

    @Override
    public EnrichedAction enrich(ActionDraft draft, PlanningContext context) {
        PlanActionItem item = baseItem(draft, context);
        int variant = variantOf(draft);
        String notes = practiceNotes(context);
        ActionLanguageService.PracticePlan practice =
                language.draftPractice(
                        goalText(draft, context), context.task().getObjective(), notes, variant);
        // 用 AI/降级返回的方案名覆盖草案标题（草案标题只是"候选方案N"占位）
        String name = practice.name();
        if (name != null && !name.isBlank()) item.setTitle(name);
        item.setInstruction(
                "练习内容："
                        + practice.practiceContent()
                        + "\n建议时长："
                        + practice.durationMinutes()
                        + " 分钟"
                        + "\n完成标准："
                        + practice.completionCriteria());
        item.setEstimatedDurationMinutes(practice.durationMinutes());
        item.setPayloadJson(
                payload(
                        Map.of(
                                "name",
                                name == null ? "" : name,
                                "variant",
                                variant,
                                "practiceContent",
                                practice.practiceContent(),
                                "durationMinutes",
                                practice.durationMinutes(),
                                "completionCriteria",
                                practice.completionCriteria())));
        return new EnrichedAction(item, List.of());
    }

    /** 从草案 hints 读取候选方案序号，缺失/非法时回退 1 */
    private int variantOf(ActionDraft draft) {
        Object raw = draft.hints() == null ? null : draft.hints().get("variant");
        if (raw == null) return 1;
        try {
            int value = Integer.parseInt(String.valueOf(raw));
            return value >= 1 ? value : 1;
        } catch (NumberFormatException ignored) {
            return 1;
        }
    }

    /** 提取练习相关输入：只保留与练习有关的行（计划内容/期望效果/频率/明确边界）， 并追加历史修改要求（重新规划时用户填的修改原因），一起作为关键词分析的依据。 */
    private String practiceNotes(PlanningContext context) {
        StringBuilder out = new StringBuilder();
        Object raw = context.parameters().get("contextNotes");
        if (raw != null) {
            for (String line : String.valueOf(raw).split("[\\r\\n]+")) {
                String trimmed = line.trim();
                if (trimmed.isBlank()) continue;
                if (trimmed.matches("^(计划内容|期望效果|频率|明确边界)[：:].*")) {
                    out.append(trimmed).append("\n");
                }
            }
        }
        if (context.revisions() != null && !context.revisions().isEmpty()) {
            out.append("历史修改要求：").append(String.join("；", context.revisions())).append("\n");
        }
        return out.toString().trim();
    }

    private String goalText(ActionDraft draft, PlanningContext context) {
        String goal = draft.goalType() == null ? "" : draft.goalType().label();
        return goal.isBlank()
                ? context.task().getObjective()
                : goal + "：" + context.task().getObjective();
    }
}
