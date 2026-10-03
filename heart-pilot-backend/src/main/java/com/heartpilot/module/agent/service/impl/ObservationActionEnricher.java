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
 * 观察型行动富化器。
 * 观察任务默认只针对"自己的反应与情境事实"，产出观察内容、记录字段与
 * 禁止推断事项；任何试图记录对方行踪/位置的监控型草案由 PlanSafetyChecker 拦截。
 */
@Service
public class ObservationActionEnricher extends AbstractActionEnricher {
    private final ActionLanguageService language;

    public ObservationActionEnricher(ActionLanguageService language, ObjectMapper json) {
        super(json);
        this.language = language;
    }

    @Override
    public boolean supports(ExecutionKind kind) {
        return kind == ExecutionKind.OBSERVATION;
    }

    @Override
    public EnrichedAction enrich(ActionDraft draft, PlanningContext context) {
        PlanActionItem item = baseItem(draft, context);
        ActionLanguageService.ObservationPlan observation =
                language.draftObservation(goalText(draft, context), context.task().getObjective());
        item.setInstruction(
                "观察内容：" + observation.observeContent()
                        + "\n记录字段：\n- " + String.join("\n- ", observation.recordFields())
                        + "\n禁止推断：" + observation.forbiddenInferences());
        item.setEstimatedDurationMinutes(10);
        item.setPayloadJson(
                payload(Map.of(
                        "observeContent",
                        observation.observeContent(),
                        "recordFields",
                        observation.recordFields(),
                        "forbiddenInferences",
                        observation.forbiddenInferences())));
        return new EnrichedAction(item, List.of());
    }

    private String goalText(ActionDraft draft, PlanningContext context) {
        String goal = draft.goalType() == null ? "" : draft.goalType().label();
        return goal.isBlank() ? context.task().getObjective() : goal + "：" + context.task().getObjective();
    }
}
