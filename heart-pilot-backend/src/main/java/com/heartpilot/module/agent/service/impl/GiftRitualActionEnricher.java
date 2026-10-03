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
 * 表达型行动富化器（礼物/仪式）。
 * 产出准备事项、预算安排与执行步骤；预算约束来自规划上下文，不自动执行任何购买。
 */
@Service
public class GiftRitualActionEnricher extends AbstractActionEnricher {
    private final ActionLanguageService language;

    public GiftRitualActionEnricher(ActionLanguageService language, ObjectMapper json) {
        super(json);
        this.language = language;
    }

    @Override
    public boolean supports(ExecutionKind kind) {
        return kind == ExecutionKind.GIFT_RITUAL;
    }

    @Override
    public EnrichedAction enrich(ActionDraft draft, PlanningContext context) {
        PlanActionItem item = baseItem(draft, context);
        ActionLanguageService.GiftPlan gift =
                language.draftGift(goalText(draft, context), context.task().getObjective(), context.budget());
        item.setInstruction(
                "准备事项：" + gift.preparation()
                        + "\n预算安排：" + gift.budgetText()
                        + "\n执行步骤：\n- " + String.join("\n- ", gift.steps()));
        item.setTimingSuggestion("选一个对方没有压力、值得被记得的日子");
        item.setPayloadJson(
                payload(Map.of(
                        "preparation",
                        gift.preparation(),
                        "budgetText",
                        gift.budgetText(),
                        "steps",
                        gift.steps())));
        return new EnrichedAction(item, List.of());
    }

    private String goalText(ActionDraft draft, PlanningContext context) {
        String goal = draft.goalType() == null ? "" : draft.goalType().label();
        return goal.isBlank() ? context.task().getObjective() : goal + "：" + context.task().getObjective();
    }
}
