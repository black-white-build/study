package com.heartpilot.module.agent.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heartpilot.module.agent.entity.enums.ExecutionKind;
import com.heartpilot.module.agent.service.PlanSafetyChecker;
import com.heartpilot.module.agent.service.PlanSafetyChecker.Decision;
import com.heartpilot.module.agent.service.PlanSafetyChecker.DraftItem;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 规划安全检查器单元测试（P11）。
 * 覆盖：边界侵犯（含伪装成观察行动的监控）、现实危险、心理诊断、
 * 消息行动内容审核、否定语境误判防护。
 */
class PlanSafetyCheckerTest {
    private final PlanSafetyChecker checker = new PlanSafetyCheckerImpl();

    private DraftItem item(ExecutionKind kind, String title, String instruction) {
        return new DraftItem(kind, title, instruction, Map.of());
    }

    @Test
    void acceptsBenignConversationPlan() {
        Decision decision =
                checker.evaluate(
                        List.of(
                                item(
                                        ExecutionKind.CONVERSATION,
                                        "坦诚沟通",
                                        "约时间聊聊感受，给对方留出回应空间")));
        assertTrue(decision.approved());
        assertEquals("SAFE", decision.reasonCode());
    }

    @Test
    void blocksSurveillanceDisguisedAsObservation() {
        // 观察型行动记录对方行踪 → 判定为监控计划，整单拒绝
        Decision decision =
                checker.evaluate(
                        List.of(
                                item(
                                        ExecutionKind.OBSERVATION,
                                        "记录对方的行踪",
                                        "记录对方每天几点回家、和谁见面")));
        assertFalse(decision.approved());
        assertEquals("BOUNDARY_VIOLATION", decision.reasonCode());
        assertEquals(List.of("记录对方的行踪"), decision.blockedTitles());
    }

    @Test
    void blocksStalkingAndHarassmentPlan() {
        Decision decision =
                checker.evaluate(
                        List.of(
                                item(ExecutionKind.PLACE_VISIT, "偶遇安排", "跟踪对方常去的地点，制造偶遇")));
        assertFalse(decision.approved());
        assertEquals("BOUNDARY_VIOLATION", decision.reasonCode());
    }

    @Test
    void doesNotFlagNegatedBoundaryWords() {
        // "不想跟踪对方" 是用户澄清边界，不是监控请求
        Decision decision =
                checker.evaluate(
                        List.of(
                                item(
                                        ExecutionKind.CONVERSATION,
                                        "沟通",
                                        "我不想跟踪对方，只是想直接聊清楚这件事")));
        assertTrue(decision.approved());
    }

    @Test
    void blocksRealWorldDangerWithSafetyGuidance() {
        Decision decision =
                checker.evaluate(
                        List.of(
                                item(ExecutionKind.CONVERSATION, "当面沟通", "如果他再威胁我，我就杀了他")));
        assertFalse(decision.approved());
        assertEquals("REAL_WORLD_DANGER", decision.reasonCode());
    }

    @Test
    void doesNotFlagNegatedDangerExpression() {
        Decision decision =
                checker.evaluate(
                        List.of(
                                item(
                                        ExecutionKind.CONVERSATION,
                                        "沟通",
                                        "我不会自杀，只是最近情绪很低落想聊聊")));
        assertTrue(decision.approved());
    }

    @Test
    void rejectsDiagnosisRequest() {
        Decision decision =
                checker.evaluate(
                        List.of(
                                item(
                                        ExecutionKind.CONVERSATION,
                                        "诊断",
                                        "帮我诊断一下他是不是自恋型人格障碍")));
        assertFalse(decision.approved());
        assertEquals("DIAGNOSIS_REQUEST", decision.reasonCode());
    }

    @Test
    void rejectsAbusiveMessageDraft() {
        Decision decision =
                checker.evaluate(
                        List.of(
                                item(
                                        ExecutionKind.MESSAGE,
                                        "发消息骂他",
                                        "给他发消息骂他一顿，羞辱他")));
        assertFalse(decision.approved());
        assertEquals("MESSAGE_ABUSE", decision.reasonCode());
    }

    @Test
    void blocksWholePlanWhenAnyItemViolates() {
        Decision decision =
                checker.evaluate(
                        List.of(
                                item(ExecutionKind.CONVERSATION, "沟通", "约时间聊聊感受"),
                                item(ExecutionKind.OBSERVATION, "暗中观察", "偷偷记录对方几点回家")));
        assertFalse(decision.approved());
        assertTrue(decision.blockedTitles().contains("沟通"));
        assertTrue(decision.blockedTitles().contains("暗中观察"));
    }
}
