package com.heartpilot.module.agent.service;

import com.heartpilot.module.agent.service.ActionEnricher.ActionDraft;
import com.heartpilot.module.agent.service.ActionEnricher.EnrichedAction;
import java.util.List;

/**
 * 行动富化编排服务（P7）。
 * 把一批行动草案按执行方式分派给对应的 ActionEnricher，产出可持久化的
 * 结构化行动条目列表；不支持的类型跳过并记录，不阻断整体规划。
 */
public interface ActionEnrichmentService {
    /**
     * 富化全部草案。
     * @param drafts 行动草案列表
     * @param context 规划上下文
     * @return 富化后的行动条目列表（未持久化），顺序与输入草案一致
     */
    List<EnrichedAction> enrichAll(List<ActionDraft> drafts, PlanningContext context);
}
