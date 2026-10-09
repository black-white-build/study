package com.heartpilot.module.agent.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.heartpilot.module.agent.entity.PlanActionItem;
import com.heartpilot.module.agent.entity.enums.RiskLevel;
import com.heartpilot.module.agent.service.ActionEnricher;
import com.heartpilot.module.agent.service.PlanningContext;
import java.util.List;
import java.util.Map;

/**
 * 富化器公共基类。 统一负责：基础条目字段填充（执行方式/目标/标题/指令）、JSON 序列化 （payload / sourceReferences），子类只实现 supports 与类型专属的
 * enrich 逻辑。
 */
public abstract class AbstractActionEnricher implements ActionEnricher {
    protected final ObjectMapper json;

    protected AbstractActionEnricher(ObjectMapper json) {
        this.json = json;
    }

    /** 构造基础条目：类型、目标、标题（缺省用类型名）、指令、默认低风险 */
    protected PlanActionItem baseItem(ActionDraft draft, PlanningContext context) {
        PlanActionItem item = new PlanActionItem();
        item.setExecutionKind(draft.kind());
        item.setGoalType(draft.goalType());
        item.setTitle(
                draft.title() == null || draft.title().isBlank()
                        ? draft.kind().label()
                        : draft.title());
        item.setInstruction(draft.instruction());
        item.setRiskLevel(RiskLevel.LOW);
        item.setPayloadJson("{}");
        item.setSourceReferencesJson("[]");
        return item;
    }

    /** 把 payload Map 序列化为 JSON 字符串 */
    protected String payload(Map<String, Object> data) {
        try {
            return json.writeValueAsString(data);
        } catch (Exception ignored) {
            return "{}";
        }
    }

    /** 把引用来源列表序列化为 JSON 字符串数组 */
    protected String refs(List<String> sourceReferences) {
        try {
            return json.writeValueAsString(sourceReferences == null ? List.of() : sourceReferences);
        } catch (Exception ignored) {
            return "[]";
        }
    }
}
