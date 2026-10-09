package com.heartpilot.module.agent.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.heartpilot.module.agent.entity.PlanActionItem;
import com.heartpilot.module.agent.service.ActionEnricher;
import com.heartpilot.module.agent.service.ActionEnricher.ActionDraft;
import com.heartpilot.module.agent.service.ActionEnricher.EnrichedAction;
import com.heartpilot.module.agent.service.ActionEnrichmentService;
import com.heartpilot.module.agent.service.PlanningContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 行动富化编排服务实现。 按执行方式把草案分派给对应的 ActionEnricher： - 未注册类型的草案生成降级条目（保留指令，payload 标记 UNSUPPORTED），不阻断整体规划
 * - 单个富化器失败（工具调用异常等）时生成降级条目并继续，其余行动不受影响 条目顺序与输入草案一致，sequenceNo 从 1 开始。
 */
@Service
public class ActionEnrichmentServiceImpl implements ActionEnrichmentService {
    private final Map<com.heartpilot.module.agent.entity.enums.ExecutionKind, ActionEnricher>
            registry = new LinkedHashMap<>();
    private final ObjectMapper json;

    public ActionEnrichmentServiceImpl(List<ActionEnricher> enrichers, ObjectMapper json) {
        for (ActionEnricher enricher : enrichers) {
            // 按类型注册；同一类型多个实现时后者覆盖前者（由 bean 顺序决定）
            for (com.heartpilot.module.agent.entity.enums.ExecutionKind kind :
                    com.heartpilot.module.agent.entity.enums.ExecutionKind.values()) {
                if (enricher.supports(kind)) registry.put(kind, enricher);
            }
        }
        this.json = json;
    }

    @Override
    public List<EnrichedAction> enrichAll(List<ActionDraft> drafts, PlanningContext context) {
        List<EnrichedAction> result = new ArrayList<>();
        if (drafts == null) return result;
        int sequence = 1;
        for (ActionDraft draft : drafts) {
            ActionEnricher enricher = registry.get(draft.kind());
            if (enricher == null) {
                result.add(unsupported(draft, sequence));
            } else {
                try {
                    EnrichedAction enriched = enricher.enrich(draft, context);
                    enriched.item().setSequenceNo(sequence);
                    result.add(enriched);
                } catch (Exception failure) {
                    result.add(degraded(draft, sequence, failure));
                }
            }
            sequence++;
        }
        return result;
    }

    /** 未注册类型的降级条目 */
    private EnrichedAction unsupported(ActionDraft draft, int sequence) {
        PlanActionItem item = new PlanActionItem();
        item.setExecutionKind(draft.kind());
        item.setGoalType(draft.goalType());
        item.setTitle(
                draft.title() == null || draft.title().isBlank()
                        ? draft.kind().label()
                        : draft.title());
        item.setInstruction(draft.instruction());
        item.setSequenceNo(sequence);
        item.setPayloadJson(write(Map.of("status", "UNSUPPORTED", "note", "暂不支持该行动类型的自动生成")));
        return new EnrichedAction(item, List.of());
    }

    /** 富化失败的降级条目：保留指令，payload 记录失败原因 */
    private EnrichedAction degraded(ActionDraft draft, int sequence, Exception failure) {
        PlanActionItem item = new PlanActionItem();
        item.setExecutionKind(draft.kind());
        item.setGoalType(draft.goalType());
        item.setTitle(
                draft.title() == null || draft.title().isBlank()
                        ? draft.kind().label()
                        : draft.title());
        item.setInstruction(
                (draft.instruction() == null ? "" : draft.instruction() + "\n")
                        + "该行动的信息检索暂时不可用，可在确认前补充或修改。");
        item.setSequenceNo(sequence);
        item.setPayloadJson(
                write(
                        Map.of(
                                "status",
                                "ENRICHMENT_FAILED",
                                "message",
                                failure.getMessage() == null ? "未知错误" : failure.getMessage())));
        return new EnrichedAction(item, List.of());
    }

    private String write(Map<String, Object> data) {
        try {
            return json.writeValueAsString(data);
        } catch (Exception ignored) {
            return "{}";
        }
    }
}
