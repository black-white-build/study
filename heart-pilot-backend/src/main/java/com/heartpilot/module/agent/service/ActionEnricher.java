package com.heartpilot.module.agent.service;

import com.heartpilot.module.agent.entity.enums.ExecutionKind;
import com.heartpilot.module.agent.entity.enums.GoalType;
import java.util.Map;

/**
 * 行动条目富化器（P7）。
 * 每种执行方式对应一个策略实现：把"行动草案"（做什么）富化为
 * "可执行的结构化行动条目"（怎么做：地点、消息草稿、沟通脚本、练习、观察等）。
 *
 * 与 AgentToolExecutor 的关系：AgentToolExecutor 是通用工具调用基础设施
 * （幂等/超时/重试/审计），富化器是编排层，内部通过它调用外部工具。
 */
public interface ActionEnricher {
    /** 该富化器支持的行动草案类型 */
    record ActionDraft(
            ExecutionKind kind,
            GoalType goalType,
            String title,
            String instruction,
            Map<String, Object> hints) {}

    /** 富化结果：一条可持久化的行动条目 + 引用来源 */
    record EnrichedAction(com.heartpilot.module.agent.entity.PlanActionItem item,
                          java.util.List<String> sourceReferences) {}

    /** 是否支持某种执行方式 */
    boolean supports(ExecutionKind kind);

    /**
     * 把行动草案富化为结构化条目（填充 payload 等字段）。
     *
     * @param draft 行动草案
     * @param context 规划上下文（任务、城市、预算、步骤号等）
     * @return 富化后的行动条目
     * @throws Exception 富化过程中的底层异常（工具调用失败等）
     */
    EnrichedAction enrich(ActionDraft draft, PlanningContext context) throws Exception;
}
