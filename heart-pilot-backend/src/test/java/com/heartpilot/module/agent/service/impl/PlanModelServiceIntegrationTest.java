package com.heartpilot.module.agent.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heartpilot.module.agent.entity.ActionPlan;
import com.heartpilot.module.agent.entity.AgentTask;
import com.heartpilot.module.agent.entity.PlanActionItem;
import com.heartpilot.module.agent.entity.PlanVersion;
import com.heartpilot.module.agent.entity.enums.ActionPlanStatus;
import com.heartpilot.module.agent.entity.enums.AgentTaskStatus;
import com.heartpilot.module.agent.entity.enums.ExecutionKind;
import com.heartpilot.module.agent.entity.enums.GoalType;
import com.heartpilot.module.agent.entity.enums.PlanVersionStatus;
import com.heartpilot.module.agent.entity.enums.RiskLevel;
import com.heartpilot.module.agent.repository.TaskRepository;
import com.heartpilot.module.agent.service.ActionEnricher.EnrichedAction;
import com.heartpilot.module.agent.service.PlanModelService;
import com.heartpilot.module.user.entity.AppUser;
import com.heartpilot.module.user.repository.AppUserRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 计划产物模型集成测试（P4）。 验证三张新表在真实 JPA 映射下的完整生命周期： 创建计划 → 保存草稿版本与条目 → 确认（APPROVED）→ 重规划新版本（历史保留）→ 驳回草稿 →
 * 删除任务级联清理。
 */
@SpringBootTest
class PlanModelServiceIntegrationTest {
    @Autowired PlanModelService planModel;
    @Autowired TaskRepository tasks;
    @Autowired AppUserRepository users;

    @Test
    void planLifecyclePersistsVersionsAndItems() {
        AppUser user = new AppUser();
        user.setUsername("plan-" + System.nanoTime());
        user.setPasswordHash("not-used-in-this-test");
        user.setNickname("计划测试");
        user = users.save(user);

        AgentTask task = new AgentTask();
        task.setUserId(user.getId());
        task.setTitle("测试行动计划");
        task.setObjective("想修复一次吵架后的关系，约出来聊聊");
        task.setStatus(AgentTaskStatus.WAITING);
        task.setVersionNo(0);
        task.setCurrentStep(0);
        task.setMaxSteps(10);
        task.setParametersJson("{}");
        task = tasks.save(task);

        // 保存第一版草稿：地点行动 + 消息行动共存
        PlanActionItem place = new PlanActionItem();
        place.setExecutionKind(ExecutionKind.PLACE_VISIT);
        place.setGoalType(GoalType.REPAIR);
        place.setTitle("咖啡店见面");
        place.setInstruction("选一家安静的咖啡店见面");
        place.setPayloadJson("{\"placeName\":\"测试咖啡店\"}");
        place.setRiskLevel(RiskLevel.LOW);

        PlanActionItem message = new PlanActionItem();
        message.setExecutionKind(ExecutionKind.MESSAGE);
        message.setGoalType(GoalType.REPAIR);
        message.setTitle("先发一条消息");
        message.setInstruction("用事实+感受+请求写一条短消息");
        message.setPayloadJson("{\"draft\":\"我想和你聊聊最近的事\"}");
        message.setRiskLevel(RiskLevel.LOW);

        PlanVersion draftV0 =
                planModel.saveDraft(
                        task,
                        GoalType.REPAIR,
                        List.of(
                                new EnrichedAction(place, List.of("https://example.test/place")),
                                new EnrichedAction(message, List.of())),
                        "7000 元");

        ActionPlan plan = planModel.findByTask(task);
        assertNotNull(plan);
        assertEquals(0, draftV0.getVersionNo());
        assertEquals(PlanVersionStatus.DRAFT, draftV0.getStatus());
        assertTrue(draftV0.getPreviewText().contains("预算上限：7000 元"));
        assertTrue(draftV0.getPreviewText().contains("咖啡店见面"));
        assertTrue(draftV0.getPreviewText().contains("先发一条消息"));
        assertEquals(2, planModel.itemsOf(draftV0).size());

        // 确认第一版 → APPROVED
        planModel.approveCurrentDraft(task, "正式计划全文");
        PlanVersion approved = planModel.versions(plan.getId()).getFirst();
        assertEquals(PlanVersionStatus.APPROVED, approved.getStatus());
        assertEquals("正式计划全文", approved.getFullText());
        assertEquals(ActionPlanStatus.APPROVED, planModel.findByTask(task).getStatus());

        // 用户驳回后重规划（模拟任务版本号自增）→ 新草稿版本，历史版本保留
        task.setVersionNo(1);
        tasks.save(task);
        PlanActionItem conversation = new PlanActionItem();
        conversation.setExecutionKind(ExecutionKind.CONVERSATION);
        conversation.setGoalType(GoalType.REPAIR);
        conversation.setTitle("当面沟通");
        conversation.setInstruction("用开场白表达感受");
        conversation.setPayloadJson("{\"opening\":\"最近有一件事想和你聊聊\"}");
        PlanVersion draftV1 =
                planModel.saveDraft(
                        task,
                        GoalType.REPAIR,
                        List.of(new EnrichedAction(conversation, List.of())),
                        "未限定");

        assertEquals(1, draftV1.getVersionNo());
        List<PlanVersion> allVersions = planModel.versions(plan.getId());
        assertEquals(2, allVersions.size());
        // 最新版本（版本 1）为草稿，历史版本 0 已确认，未被覆盖
        assertEquals(1, allVersions.getFirst().getVersionNo());
        assertEquals(PlanVersionStatus.DRAFT, allVersions.getFirst().getStatus());
        assertEquals(0, allVersions.getLast().getVersionNo());
        assertEquals(PlanVersionStatus.APPROVED, allVersions.getLast().getStatus());

        // 驳回草稿版本 1
        planModel.rejectCurrentDraft(task, "想换一种方式");
        assertEquals(
                PlanVersionStatus.REJECTED,
                planModel.versions(plan.getId()).getFirst().getStatus());

        // 删除任务 → 计划、版本、条目全部级联清理（任务本身仍保留）
        long planId = plan.getId();
        planModel.deleteByTask(task.getId());
        assertEquals(null, planModel.findByTask(task));
        assertTrue(planModel.versions(planId).isEmpty());
    }
}
