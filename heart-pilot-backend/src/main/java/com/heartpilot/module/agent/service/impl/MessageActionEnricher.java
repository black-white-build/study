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
 * 消息型行动富化器。
 * 调用文案生成服务产出消息草稿（文本/语气/发送时机/禁用表达），
 * 全程不调用地点或地图工具，满足"消息型计划不调用地点工具"的验收要求。
 */
@Service
public class MessageActionEnricher extends AbstractActionEnricher {
    private final ActionLanguageService language;

    public MessageActionEnricher(ActionLanguageService language, ObjectMapper json) {
        super(json);
        this.language = language;
    }

    @Override
    public boolean supports(ExecutionKind kind) {
        return kind == ExecutionKind.MESSAGE;
    }

    @Override
    public EnrichedAction enrich(ActionDraft draft, PlanningContext context) {
        PlanActionItem item = baseItem(draft, context);
        ActionLanguageService.MessageDraft message =
                language.draftMessage(goalText(draft, context), context.task().getObjective());
        item.setInstruction(
                "发送这条消息：\n" + message.text()
                        + "\n\n语气：" + message.tone()
                        + "\n建议时机：" + message.sendTiming()
                        + "\n注意：保持平和真诚，避免指责、翻旧账等易激化冲突的表达。");
        item.setTimingSuggestion(message.sendTiming());
        item.setEstimatedDurationMinutes(10);
        item.setPayloadJson(
                payload(Map.of(
                        "draft",
                        message.text(),
                        "tone",
                        message.tone(),
                        "sendTiming",
                        message.sendTiming(),
                        "forbiddenExpressions",
                        message.forbiddenExpressions())));
        return new EnrichedAction(item, List.of());
    }

    private String goalText(ActionDraft draft, PlanningContext context) {
        String goal = draft.goalType() == null ? "" : draft.goalType().label();
        return goal.isBlank() ? context.task().getObjective() : goal + "：" + context.task().getObjective();
    }
}
