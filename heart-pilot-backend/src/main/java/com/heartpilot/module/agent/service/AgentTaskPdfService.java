package com.heartpilot.module.agent.service;

import com.heartpilot.module.agent.entity.AgentTask;
import com.heartpilot.module.file.entity.GeneratedFile;

public interface AgentTaskPdfService {
    GeneratedFile generate(AgentTask task);

    GeneratedFile get(Long userId, Long taskId);

    void invalidate(AgentTask task);
}
