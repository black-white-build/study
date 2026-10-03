package com.heartpilot.module.agent.service;

import com.heartpilot.module.agent.entity.ActionPlan;
import com.heartpilot.module.agent.entity.AgentTask;
import com.heartpilot.module.agent.entity.PlanActionItem;
import com.heartpilot.module.agent.entity.PlanVersion;
import com.heartpilot.module.agent.entity.enums.GoalType;
import com.heartpilot.module.agent.service.ActionEnricher.EnrichedAction;
import java.util.List;

/**
 * 计划产物管理服务（P4/P6）。
 * 负责 action_plan / plan_version / plan_action_item 三个产物的生命周期：
 * - 懒创建计划聚合根（一个任务一个）
 * - 每次规划保存一个新的 DRAFT 版本（版本号与任务 versionNo 对齐），旧版本只读保留
 * - 用户确认后把当前草稿置为 APPROVED（正式计划），并刷新计划整体状态
 * - 用户驳回时把当前草稿置为 REJECTED 并记录原因
 * - 删除任务时级联清理计划、版本与条目
 */
public interface PlanModelService {
    /** 按任务查询计划，不存在则创建（1:1） */
    ActionPlan getOrCreate(AgentTask task);

    /** 查询任务对应的计划（可能为空） */
    ActionPlan findByTask(AgentTask task);

    /**
     * 保存一份新的 DRAFT 版本：创建版本 + 落库全部行动条目 + 渲染预览文本。
     * @param budgetLabelText 预算约束的展示文案（如"7000 元"/"未限定"），写入预览
     * @return 新创建的草稿版本
     */
    PlanVersion saveDraft(
            AgentTask task, GoalType goalType, List<EnrichedAction> enriched, String budgetLabelText);

    /** 查询计划下的全部版本，最新在前 */
    List<PlanVersion> versions(Long planId);

    /** 查询某个版本的行动条目，按顺序 */
    List<PlanActionItem> itemsOf(PlanVersion version);

    /** 用户确认：当前草稿置为 APPROVED，写正式全文；之前已确认的版本标记为 SUPERSEDED */
    void approveCurrentDraft(AgentTask task, String fullText);

    /** 用户驳回：当前草稿置为 REJECTED 并记录原因 */
    void rejectCurrentDraft(AgentTask task, String note);

    /** 删除任务时级联清理计划、版本与条目 */
    void deleteByTask(Long taskId);

    /** 把行动条目渲染为候选计划预览文本（Markdown），含预算约束行 */
    String renderPreview(List<PlanActionItem> items, String budgetLabelText);
}
