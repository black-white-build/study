package com.heartpilot.module.agent.service;

import com.heartpilot.module.agent.entity.AgentTask;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public interface AgentTaskStepService {
    void complete(AgentTask task, int number, String detail, SseEmitter emitter);

    void start(AgentTask task, int number, String detail, SseEmitter emitter);

    void finish(AgentTask task, int number, String detail, SseEmitter emitter);

    void reset(Long taskId);
}
