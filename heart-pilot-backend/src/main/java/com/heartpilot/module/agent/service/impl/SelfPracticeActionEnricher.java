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
 * 自我练习型行动富化器。
 * 产出面向自己的练习内容、建议时长与完成标准；不要求改变对方，不诊断他人。
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
        ActionLanguageService.PracticePlan practice =
                language.draftPractice(goalText(draft, context), context.task().getObjective());
        item.setInstruction(
                "练习内容：" + practice.practiceContent()
                        + "\n建议时长：" + practice.durationMinutes() + " 分钟"
                        + "\n完成标准：" + practice.completionCriteria());
        item.setEstimatedDurationMinutes(practice.durationMinutes());
        item.setPayloadJson(
                payload(Map.of(
                        "practiceContent",
                        practice.practiceContent(),
                        "durationMinutes",
                        practice.durationMinutes(),
                        "completionCriteria",
                        practice.completionCriteria())));
        return new EnrichedAction(item, List.of());
    }

    private String goalText(ActionDraft draft, PlanningContext context) {
        String goal = draft.goalType() == null ? "" : draft.goalType().label();
        return goal.isBlank() ? context.task().getObjective() : goal + "：" + context.task().getObjective();
    }
}
