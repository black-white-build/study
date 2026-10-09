package com.heartpilot.module.agent.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heartpilot.module.agent.entity.AgentTask;
import com.heartpilot.module.agent.entity.PlanActionItem;
import com.heartpilot.module.agent.entity.enums.AgentTaskStatus;
import com.heartpilot.module.agent.entity.enums.ExecutionKind;
import com.heartpilot.module.agent.service.AgentTaskService;
import com.heartpilot.module.user.entity.AppUser;
import com.heartpilot.module.user.repository.AppUserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 行动类型路由集成测试（P12）。 验证按行动类型分派工具的真实性约束： 1. 消息型计划不调用地点工具、不产生地点证据、轨迹中无 ROUTE 阶段 2.
 * 混合计划中地点型行动调用地图工具，且地点/消息/沟通/练习多种类型共存于同一版本
 */
@SpringBootTest
class ActionTypeRoutingIntegrationTest {
    @Autowired AgentTaskService tasks;
    @Autowired AppUserRepository users;

    private AppUser newUser() {
        AppUser user = new AppUser();
        user.setUsername("routing-" + System.nanoTime());
        user.setPasswordHash("not-used-in-this-test");
        user.setNickname("路由测试");
        return users.save(user);
    }

    private AgentTaskService.TaskDetail awaitConfirmation(long taskId, long userId) {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(8));
        AgentTaskService.TaskDetail detail;
        do {
            try {
                Thread.sleep(50);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
            detail = tasks.get(taskId, userId);
        } while (detail.task().getStatus() != AgentTaskStatus.AWAITING_CONFIRMATION
                && !detail.task().getStatus().isTerminal()
                && Instant.now().isBefore(deadline));
        return detail;
    }

    @Test
    void messageOnlyPlanDoesNotCallPlaceTools() throws Exception {
        AppUser user = newUser();
        // 无地点约束：create 不要求省/市/问题列表
        AgentTask task =
                tasks.create(
                        user.getId(),
                        "先发一条消息",
                        "想发个消息问问对方最近怎么样",
                        Map.of(),
                        "routing-msg-" + System.nanoTime());

        tasks.run(task.getId(), user.getId());
        AgentTaskService.TaskDetail detail = awaitConfirmation(task.getId(), user.getId());

        assertEquals(
                AgentTaskStatus.AWAITING_CONFIRMATION,
                detail.task().getStatus(),
                detail.task().getErrorMessage());
        // 消息型计划不得调用任何地点/地图工具
        assertTrue(detail.toolCalls().isEmpty(), "消息型计划不应产生任何工具调用");
        // 不得产生地点证据
        assertNull(detail.task().getJourneyEvidenceJson());
        // 轨迹中不得出现 ROUTE（路线计算）阶段
        assertTrue(
                detail.executionEvents().stream()
                        .noneMatch(event -> event.getPhase().name().equals("ROUTE")),
                "消息型计划不应出现路线计算阶段");
        // 预览只包含消息行动
        assertNotNull(detail.task().getPlanPreview());
        assertTrue(detail.task().getPlanPreview().contains("消息行动"));

        // 计划产物：1 个版本、1 条消息行动条目
        AgentTaskService.PlanDetail plan = tasks.plan(task.getId(), user.getId());
        assertNotNull(plan.plan());
        assertEquals(1, plan.versions().size());
        assertEquals(1, plan.currentItems().size());
        assertEquals(ExecutionKind.MESSAGE, plan.currentItems().getFirst().getExecutionKind());
    }

    @Test
    void mixedPlanRoutesToolsByTypeAndKeepsAllKinds() throws Exception {
        AppUser user = newUser();
        AgentTask task =
                tasks.create(
                        user.getId(),
                        "吵架后的修复计划",
                        "吵架后想约她出来见面聊聊，也想发个消息先打个招呼，再练习先冷静一下",
                        Map.of(
                                "province",
                                "广西壮族自治区",
                                "city",
                                "南宁市",
                                "budget",
                                500,
                                "questions",
                                List.of("哪里适合安静聊聊")),
                        "routing-mixed-" + System.nanoTime());

        tasks.run(task.getId(), user.getId());
        AgentTaskService.TaskDetail detail = awaitConfirmation(task.getId(), user.getId());

        assertEquals(
                AgentTaskStatus.AWAITING_CONFIRMATION,
                detail.task().getStatus(),
                detail.task().getErrorMessage());
        // 地点型行动必须调用地图检索工具（AMAP 未配置时记录 FAILED，但工具调用仍被审计）
        assertTrue(
                detail.toolCalls().stream()
                        .anyMatch(call -> "plan-place-search".equals(call.getToolName())),
                "地点型计划应调用地图检索工具");

        // 多种行动类型共存于同一计划版本
        AgentTaskService.PlanDetail plan = tasks.plan(task.getId(), user.getId());
        assertNotNull(plan.plan());
        List<ExecutionKind> kinds =
                plan.currentItems().stream().map(PlanActionItem::getExecutionKind).toList();
        assertTrue(kinds.contains(ExecutionKind.PLACE_VISIT), "应包含地点行动，实际：" + kinds);
        assertTrue(kinds.contains(ExecutionKind.MESSAGE), "应包含消息行动，实际：" + kinds);
        assertTrue(kinds.contains(ExecutionKind.CONVERSATION), "应包含沟通行动，实际：" + kinds);
        assertTrue(kinds.contains(ExecutionKind.SELF_PRACTICE), "应包含自我练习，实际：" + kinds);
        assertTrue(kinds.size() >= 4, "至少 4 种行动共存，实际：" + kinds.size());
    }
}
