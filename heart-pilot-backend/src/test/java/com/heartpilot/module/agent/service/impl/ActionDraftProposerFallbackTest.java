package com.heartpilot.module.agent.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heartpilot.module.agent.entity.AgentTask;
import com.heartpilot.module.agent.entity.enums.ExecutionKind;
import com.heartpilot.module.agent.entity.enums.GoalType;
import com.heartpilot.module.agent.service.ActionDraftProposer.ActionProposal;
import com.heartpilot.module.agent.service.ActionDraftProposer;
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
}
