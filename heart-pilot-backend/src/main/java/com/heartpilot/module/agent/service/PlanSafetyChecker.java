package com.heartpilot.module.agent.service;

import com.heartpilot.module.agent.entity.enums.ExecutionKind;
import java.util.List;
import java.util.Map;

/**
 * 规划安全检查器。
 * 对 Agent 生成的计划草案（多条行动条目）做确定性（规则式）安全分流，
 * 是对话安全（AnswerSafetyPolicy）在规划链路上的补充：RAG 与模型生成的
 * 计划内容不能覆盖系统安全规则，高风险计划直接拒绝生成。
 *
 * 覆盖范围：
 * - 边界侵犯：跟踪、监控、纠缠、骚扰、冒充等（含伪装成"观察行动"的监控计划）
 * - 现实人身危险：暴力、自伤、威胁等 → 输出安全指引
 * - 心理诊断：不诊断对方的人格/心理状态
 * - 消息行动内容审核：威胁、恐吓、辱骂等表达不进入计划
 */
public interface PlanSafetyChecker {
    /**
     * 检查一条计划条目的原始输入（kind/title/instruction/payload 均来自草案阶段）。
     */
    record DraftItem(
            ExecutionKind kind, String title, String instruction, Map<String, Object> payload) {}

    /**
     * 检查结果。
     * @param approved true=通过，false=拒绝（原因见 reasonCode / message）
     * @param reasonCode 命中的原因码（BOUNDARY_VIOLATION / REAL_WORLD_DANGER / DIAGNOSIS_REQUEST / MESSAGE_ABUSE）
     * @param message 需要展示给用户的拒绝文案或安全指引
     * @param blockedTitles 被拦截的条目标题列表
     */
    record Decision(
            boolean approved, String reasonCode, String message, List<String> blockedTitles) {}

    /** 对整份计划草案执行安全检查，任一条目命中高危规则即整单拒绝 */
    Decision evaluate(List<DraftItem> items);
}
