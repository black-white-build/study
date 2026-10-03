package com.heartpilot.module.agent.service.impl;

import com.heartpilot.module.agent.entity.ActionPlan;
import com.heartpilot.module.agent.entity.AgentTask;
import com.heartpilot.module.agent.entity.PlanActionItem;
import com.heartpilot.module.agent.entity.PlanVersion;
import com.heartpilot.module.agent.entity.enums.ActionPlanStatus;
import com.heartpilot.module.agent.entity.enums.PlanVersionStatus;
import com.heartpilot.module.agent.entity.enums.GoalType;
import com.heartpilot.module.agent.repository.ActionPlanRepository;
import com.heartpilot.module.agent.repository.PlanActionItemRepository;
import com.heartpilot.module.agent.repository.PlanVersionRepository;
import com.heartpilot.module.agent.service.ActionEnricher.EnrichedAction;
import com.heartpilot.module.agent.service.PlanModelService;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 计划产物管理服务实现。
 * 所有写操作都在事务内完成：版本 + 条目一起落库，避免出现"有版本无条目"的中间态。
 * 渲染预览与正式计划文本时，每条行动按"执行方式｜行动目标"归类展示。
 */
@Service
public class PlanModelServiceImpl implements PlanModelService {
    private final ActionPlanRepository plans;
    private final PlanVersionRepository versions;
    private final PlanActionItemRepository items;

    public PlanModelServiceImpl(
            ActionPlanRepository plans,
            PlanVersionRepository versions,
            PlanActionItemRepository items) {
        this.plans = plans;
        this.versions = versions;
        this.items = items;
    }

    @Override
    public ActionPlan getOrCreate(AgentTask task) {
        return plans.findByTaskId(task.getId())
                .orElseGet(() -> {
                    ActionPlan plan = new ActionPlan();
                    plan.setTaskId(task.getId());
                    plan.setUserId(task.getUserId());
                    plan.setTitle(task.getTitle());
                    plan.setObjective(task.getObjective());
                    return plans.save(plan);
                });
    }

    @Override
    public ActionPlan findByTask(AgentTask task) {
        return plans.findByTaskId(task.getId()).orElse(null);
    }

    @Override
    @Transactional
    public PlanVersion saveDraft(
            AgentTask task,
            GoalType goalType,
            List<EnrichedAction> enriched,
            String budgetLabelText) {
        ActionPlan plan = getOrCreate(task);
        plan.setGoalType(goalType);
        plan.setStatus(ActionPlanStatus.DRAFT);
        plans.save(plan);

        PlanVersion version = new PlanVersion();
        version.setPlanId(plan.getId());
        version.setVersionNo(task.getVersionNo());
        version.setStatus(PlanVersionStatus.DRAFT);
        versions.save(version);

        int sequence = 1;
        for (EnrichedAction enrichedAction : enriched) {
            PlanActionItem item = enrichedAction.item();
            item.setPlanId(plan.getId());
            item.setVersionId(version.getId());
            item.setSequenceNo(sequence++);
            item.setSourceReferencesJson(
                    enrichedAction.sourceReferences() == null
                            ? "[]"
                            : writeRefs(enrichedAction.sourceReferences()));
            items.save(item);
        }
        List<PlanActionItem> saved = items.findByVersionIdOrderBySequenceNoAsc(version.getId());
        version.setPreviewText(renderPreview(saved, budgetLabelText));
        return versions.save(version);
    }

    @Override
    public List<PlanVersion> versions(Long planId) {
        return versions.findByPlanIdOrderByVersionNoDesc(planId);
    }

    @Override
    public List<PlanActionItem> itemsOf(PlanVersion version) {
        return items.findByVersionIdOrderBySequenceNoAsc(version.getId());
    }

    @Override
    @Transactional
    public void approveCurrentDraft(AgentTask task, String fullText) {
        ActionPlan plan = getOrCreate(task);
        // 历史已确认版本标记为已取代，保留只读历史
        versions.findByPlanIdAndStatus(plan.getId(), PlanVersionStatus.APPROVED)
                .ifPresent(previous -> {
                    previous.setStatus(PlanVersionStatus.SUPERSEDED);
                    versions.save(previous);
                });
        PlanVersion draft = versions.findByPlanIdAndStatus(plan.getId(), PlanVersionStatus.DRAFT)
                .orElseGet(() -> versions
                        .findFirstByPlanIdOrderByVersionNoDesc(plan.getId())
                        .orElseThrow());
        draft.setStatus(PlanVersionStatus.APPROVED);
        draft.setFullText(fullText);
        versions.save(draft);
        plan.setStatus(ActionPlanStatus.APPROVED);
        plans.save(plan);
    }

    @Override
    @Transactional
    public void rejectCurrentDraft(AgentTask task, String note) {
        ActionPlan plan = getOrCreate(task);
        versions.findByPlanIdAndStatus(plan.getId(), PlanVersionStatus.DRAFT)
                .ifPresent(draft -> {
                    draft.setStatus(PlanVersionStatus.REJECTED);
                    if (note != null && !note.isBlank()) draft.setNote(note);
                    versions.save(draft);
                });
    }

    @Override
    @Transactional
    public void deleteByTask(Long taskId) {
        plans.findByTaskId(taskId).ifPresent(plan -> {
            items.deleteByPlanId(plan.getId());
            versions.deleteByPlanId(plan.getId());
            plans.delete(plan);
        });
    }

    @Override
    public String renderPreview(List<PlanActionItem> planItems, String budgetLabelText) {
        if (planItems == null || planItems.isEmpty()) {
            return "计划正在生成，请稍后刷新。";
        }
        String budgetLabel = budgetLabelText == null || budgetLabelText.isBlank() ? "未限定" : budgetLabelText;
        StringBuilder out = new StringBuilder();
        out.append("## 当前候选计划\n\n### 行动目标\n")
                .append(planItems.getFirst().getGoalType() == null
                        ? "未识别"
                        : planItems.getFirst().getGoalType().label())
                .append("\n\n### 约束\n- 预算上限：")
                .append(budgetLabel)
                .append("\n\n### 行动清单\n");
        int index = 1;
        for (PlanActionItem item : planItems) {
            out.append("### ").append(index++).append(". ")
                    .append(item.getExecutionKind().label());
            if (item.getGoalType() != null) {
                out.append("｜").append(item.getGoalType().label());
            }
            out.append(" — ").append(item.getTitle()).append("\n");
            if (item.getInstruction() != null && !item.getInstruction().isBlank()) {
                out.append(item.getInstruction()).append("\n");
            }
            if (item.getTimingSuggestion() != null && !item.getTimingSuggestion().isBlank()) {
                out.append("\n**建议时机：**").append(item.getTimingSuggestion());
            }
            if (item.getEstimatedDurationMinutes() != null) {
                out.append("\n**预计耗时：**").append(item.getEstimatedDurationMinutes()).append(" 分钟");
            }
            if (item.getEstimatedCost() != null) {
                out.append("\n**预计花费：**").append(item.getEstimatedCost().toPlainString()).append(" 元");
            }
            out.append("\n\n");
        }
        out.append("### 下一步\n请核对上面的行动是否覆盖你的目标。确认后，我会生成正式计划版本；"
                + "有需要调整的行动，可直接填写修改原因重新规划。");
        return out.toString().trim();
    }

    /** 引用来源列表序列化为 JSON 字符串数组 */
    private String writeRefs(List<String> sourceReferences) {
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < sourceReferences.size(); i++) {
            if (i > 0) out.append(',');
            out.append('"')
                    .append(sourceReferences.get(i)
                            .replace("\\", "\\\\")
                            .replace("\"", "\\\""))
                    .append('"');
        }
        return out.append(']').toString();
    }
}
