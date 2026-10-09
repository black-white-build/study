package com.heartpilot.module.agent.service;

import com.heartpilot.module.agent.entity.AgentTask;
import java.util.List;
import java.util.Map;

/**
 * 规划上下文：一次规划任务在执行过程中的共享数据。 由规划流程（P6）构造后传给 ActionDraftProposer 与各 ActionEnricher，
 * 携带任务、城市/预算/问题等参数与当前步骤号，供工具调用与轨迹记录使用。
 */
public record PlanningContext(
        AgentTask task,
        String city,
        String budget,
        List<String> questions,
        List<String> revisions,
        Map<String, Object> parameters,
        int stepNo) {}
