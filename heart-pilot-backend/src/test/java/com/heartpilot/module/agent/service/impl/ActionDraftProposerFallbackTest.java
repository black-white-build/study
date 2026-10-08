package com.heartpilot.module.agent.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heartpilot.module.agent.entity.AgentTask;
import com.heartpilot.module.agent.entity.enums.ExecutionKind;
import com.heartpilot.module.agent.entity.enums.GoalType;
import com.heartpilot.module.agent.service.ActionDraftProposer.ActionProposal;
import com.heartpilot.module.agent.service.ActionDraftProposer;
import com.heartpilot.module.agent.service.ActionEnricher.ActionDraft;
import com.heartpilot.module.agent.service.PlanningContext;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 行动草案提议器降级路径测试（P7）。
 * 测试环境 API Key 为 not-configured，全部走规则降级分支：
 * 有城市默认地点行动、按关键词识别消息/沟通/练习等类型、
 * 无城市且无关键词时兜底为一次坦诚沟通。
 */
@SpringBootTest
class ActionDraftProposerFallbackTest {
    @Autowired ActionDraftProposer proposer;

    private PlanningContext context(String objective, String city, String... questions) {
        AgentTask task = new AgentTask();
        task.setTitle("测试");
        task.setObjective(objective);
        task.setVersionNo(0);
        return new PlanningContext(
                task, city, "未限定", List.of(questions), List.of(), Map.of(), 3);
    }

    private PlanningContext contextWithParams(String objective, Map<String, Object> parameters) {
        AgentTask task = new AgentTask();
        task.setTitle("测试");
        task.setObjective(objective);
        task.setVersionNo(0);
        return new PlanningContext(
                task, "", "未限定", List.of(), List.of(), parameters, 3);
    }

    @Test
    void proposesPlaceActionWhenCityProvided() {
        ActionProposal proposal =
                proposer.propose(context("在南宁安排一次安静散步", "南宁市"));
        assertTrue(
                proposal.drafts().stream()
                        .anyMatch(draft -> draft.kind() == ExecutionKind.PLACE_VISIT));
        assertTrue(proposal.drafts().size() >= 1);
    }

    @Test
    void identifiesMessageActionFromObjective() {
        ActionProposal proposal =
                proposer.propose(context("想发个消息问问对方最近怎么样", ""));
        assertTrue(
                proposal.drafts().stream()
                        .anyMatch(draft -> draft.kind() == ExecutionKind.MESSAGE));
    }

    @Test
    void identifiesConversationAndRepairGoal() {
        ActionProposal proposal =
                proposer.propose(context("吵架之后想约她出来把话说开，修复关系", ""));
        assertTrue(
                proposal.drafts().stream()
                        .anyMatch(draft -> draft.kind() == ExecutionKind.CONVERSATION));
        assertEquals(GoalType.REPAIR, proposal.goalType());
    }

    @Test
    void identifiesSelfPracticeAndGrowthGoal() {
        ActionProposal proposal =
                proposer.propose(context("想练习管理自己的情绪，最近总是焦虑", ""));
        assertTrue(
                proposal.drafts().stream()
                        .anyMatch(draft -> draft.kind() == ExecutionKind.SELF_PRACTICE));
        assertEquals(GoalType.SELF_GROWTH, proposal.goalType());
    }

    @Test
    void fallsBackToConversationWithoutCityOrKeywords() {
        ActionProposal proposal = proposer.propose(context("就是想做点什么", ""));
        assertEquals(1, proposal.drafts().size());
        assertEquals(ExecutionKind.CONVERSATION, proposal.drafts().getFirst().kind());
        assertEquals(GoalType.CONNECTION, proposal.goalType());
        assertTrue(proposal.drafts().getFirst().title().contains("坦诚的沟通"));
    }

    @Test
    void selfPracticeHintsGenerateThreeKeywordVariants() {
        // 自我计划功能实际路径：前端传 goalType + preferredActionKinds + contextNotes
        Map<String, Object> parameters =
                Map.of(
                        "goalType", "SELF_GROWTH",
                        "preferredActionKinds", List.of("SELF_PRACTICE"),
                        "contextNotes", "计划内容：运动\n期望效果：减肥\n频率：每日");
        ActionProposal proposal =
                proposer.propose(contextWithParams("运动", parameters));
        List<ActionDraft> self =
                proposal.drafts().stream()
                        .filter(draft -> draft.kind() == ExecutionKind.SELF_PRACTICE)
                        .toList();
        // 多候选：默认生成 3 套差异化方案，不再只给 1 个计划
        assertEquals(3, self.size());
        // 每套方案带递增 variant 序号
        for (int i = 0; i < self.size(); i++) {
            assertEquals(i + 1, self.get(i).hints().get("variant"));
            assertTrue(String.valueOf(self.get(i).title()).contains("候选方案"));
            // 标题与指令必须引用用户关键词（运动/减肥），不得套用情绪复盘等无关模板
            String titleAndInstruction =
                    self.get(i).title() + " " + self.get(i).instruction();
            assertTrue(
                    titleAndInstruction.contains("运动") || titleAndInstruction.contains("减肥"),
                    "草案必须引用用户关键词：" + titleAndInstruction);
        }
    }

    @Test
    void selfPracticeFallbackGeneratesThreeVariantsOnSportKeywords() {
        ActionProposal proposal = proposer.propose(context("想运动减肥，养成规律锻炼习惯", ""));
        List<ActionDraft> self =
                proposal.drafts().stream()
                        .filter(draft -> draft.kind() == ExecutionKind.SELF_PRACTICE)
                        .toList();
        // 降级路径同样多候选：3 套差异化方案
        assertEquals(3, self.size());
        for (ActionDraft draft : self) {
            String titleAndInstruction = draft.title() + " " + draft.instruction();
            assertTrue(
                    titleAndInstruction.contains("运动") || titleAndInstruction.contains("减肥"),
                    "降级草案必须引用关键词：" + titleAndInstruction);
        }
    }
}
