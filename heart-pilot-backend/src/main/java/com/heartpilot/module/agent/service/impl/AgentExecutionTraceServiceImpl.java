package com.heartpilot.module.agent.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.heartpilot.module.agent.entity.AgentExecutionEvent;
import com.heartpilot.module.agent.entity.enums.AgentExecutionEventStatus;
import com.heartpilot.module.agent.entity.enums.AgentExecutionEventType;
import com.heartpilot.module.agent.entity.enums.AgentExecutionPhase;
import com.heartpilot.module.agent.repository.AgentExecutionEventRepository;
import com.heartpilot.module.agent.service.AgentExecutionTraceService;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgentExecutionTraceServiceImpl implements AgentExecutionTraceService {
    private final AgentExecutionEventRepository events;
    private final ObjectMapper json;

    public AgentExecutionTraceServiceImpl(AgentExecutionEventRepository events, ObjectMapper json) {
        this.events = events;
        this.json = json;
    }

    @Override
    public List<AgentExecutionEvent> list(Long taskId) {
        return events.findByTaskIdOrderByCreatedAtAsc(taskId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Override
    public AgentExecutionEvent record(
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
            Map<String, ?> metadata) {
        AgentExecutionEvent event = new AgentExecutionEvent();
        event.setTaskId(taskId);
        event.setTaskVersion(taskVersion);
        event.setStepNo(stepNo);
        event.setPhase(phase);
        event.setEventType(eventType);
        event.setStatus(status);
        event.setTitle(shorten(title, 160));
        event.setDetail(detail);
        event.setProvider(shorten(provider, 80));
        event.setToolName(shorten(toolName, 80));
        event.setItemCount(itemCount);
        event.setDurationMs(durationMs);
        event.setSourceUrl(shorten(sourceUrl, 500));
        event.setMetadataJson(writeMetadata(metadata));
        return events.save(event);
    }

    @Transactional
    @Override
    public void deleteByTaskId(Long taskId) {
        events.deleteByTaskId(taskId);
    }

    private String writeMetadata(Map<String, ?> metadata) {
        if (metadata == null || metadata.isEmpty()) return null;
        try {
            return json.writeValueAsString(metadata);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String shorten(String value, int maxLength) {
        if (value == null) return null;
        return value.substring(0, Math.min(value.length(), maxLength));
    }
}
