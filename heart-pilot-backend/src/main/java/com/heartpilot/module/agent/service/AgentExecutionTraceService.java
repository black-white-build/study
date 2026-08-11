package com.heartpilot.module.agent.service;

import com.heartpilot.module.agent.entity.AgentExecutionEvent;
import com.heartpilot.module.agent.entity.enums.AgentExecutionEventStatus;
import com.heartpilot.module.agent.entity.enums.AgentExecutionEventType;
import com.heartpilot.module.agent.entity.enums.AgentExecutionPhase;
import java.util.List;
import java.util.Map;

public interface AgentExecutionTraceService {
    List<AgentExecutionEvent> list(Long taskId);

    AgentExecutionEvent record(
            Long taskId,
            int taskVersion,
            Integer stepNo,
            AgentExecutionPhase phase,
            AgentExecutionEventType eventType,
            AgentExecutionEventStatus status,
            String title,
            String detail,
            String provider,
            String toolName,
            Integer itemCount,
            Long durationMs,
            String sourceUrl,
            Map<String, ?> metadata);

    void deleteByTaskId(Long taskId);
}
