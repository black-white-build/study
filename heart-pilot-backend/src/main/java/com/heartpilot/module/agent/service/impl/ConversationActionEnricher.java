package com.heartpilot.module.agent.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.heartpilot.module.agent.entity.PlanActionItem;
import com.heartpilot.module.agent.entity.enums.ExecutionKind;
import com.heartpilot.module.agent.service.ActionLanguageService;
import com.heartpilot.module.agent.service.PlanningContext;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** 沟通型行动富化器。 产出沟通脚本（目标、开场白、关键表达、具体请求、退出条件）， 供用户按脚本执行一次当面/语音沟通。 */
@Service
public class ConversationActionEnricher extends AbstractActionEnricher {
    private final ActionLanguageService language;

    public ConversationActionEnricher(ActionLanguageService language, ObjectMapper json) {
        super(json);
        this.language = language;
    }

    @Override
    public boolean supports(ExecutionKind kind) {
        return kind == ExecutionKind.CONVERSATION;
    }

    @Override
    public EnrichedAction enrich(ActionDraft draft, PlanningContext context) {
        PlanActionItem item = baseItem(draft, context);
        ActionLanguageService.ConversationScript script =
                language.draftConversation(goalText(draft, context), context.task().getObjective());
        item.setInstruction(
                "沟通目标："
                        + script.goal()
                        + "\n开场白："
                        + script.opening()
                        + "\n关键表达：\n- "
                        + String.join("\n- ", script.keyExpressions())
                        + "\n具体请求："
                        + script.concreteRequest()
                        + "\n退出条件："
                        + script.exitCondition());
        item.setEstimatedDurationMinutes(25);
        item.setTimingSuggestion("双方都放松、不被打扰的时间");
        item.setPayloadJson(
                payload(
                        Map.of(
                                "goal",
                                script.goal(),
                                "opening",
                                script.opening(),
                                "keyExpressions",
                                script.keyExpressions(),
                                "concreteRequest",
                                script.concreteRequest(),
                                "exitCondition",
                                script.exitCondition())));
        return new EnrichedAction(item, List.of());
    }

    private String goalText(ActionDraft draft, PlanningContext context) {
        String goal = draft.goalType() == null ? "" : draft.goalType().label();
        return goal.isBlank()
                ? context.task().getObjective()
                : goal + "：" + context.task().getObjective();
    }
}
