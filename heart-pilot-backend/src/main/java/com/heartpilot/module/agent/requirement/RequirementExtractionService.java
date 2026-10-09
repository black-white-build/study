package com.heartpilot.module.agent.requirement;

import com.heartpilot.module.agent.entity.AgentTask;
import java.util.Map;

/**
 * 结构化需求抽取服务（需求解析 Agent，Tier1 公共底层）。
 *
 * <p>职责：把用户原始自然语言（目标 + 参数）单独交给 LLM， 输出固定 Schema 的结构化对象（四类约束 + 业务实体）， 与"方案生成"彻底解耦——方案生成 LLM
 * 只消费结构化对象，不再直接读原始文本。
 *
 * <p>可靠性设计：
 *
 * <ul>
 *   <li>大模型未配置或调用失败 → 规则降级抽取（基于已填写的结构化参数），标记 aiGenerated=false
 *   <li>模型输出非法（类型缺失/实体缺失）→ 携带错误信息回传重试，最多 2 次，仍失败走降级
 *   <li>支持传入 prior（已持久化的结构化需求）：模型在 prior 基础上做增量解析， 用户单条修改约束时不会丢其他已确认条件
 * </ul>
 */
public interface RequirementExtractionService {

    /**
     * 抽取结构化需求。
     *
     * @param task 任务实体（目标文本等）
     * @param parameters 任务参数（城市、预算、问题、背景、行动类型等）
     * @param prior 已持久化的结构化需求（可为 null；非 null 时作为增量解析基础）
     * @return 结构化需求对象
     */
    StructuredRequirement extract(
            AgentTask task, Map<String, Object> parameters, StructuredRequirement prior);
}
