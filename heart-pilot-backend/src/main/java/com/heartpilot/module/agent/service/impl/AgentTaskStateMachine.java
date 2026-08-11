package com.heartpilot.module.agent.service.impl;

import com.heartpilot.common.exception.ApiException;
import com.heartpilot.module.agent.entity.AgentTask;
import com.heartpilot.module.agent.entity.enums.AgentTaskStatus;
import com.heartpilot.module.agent.repository.TaskRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import org.springframework.stereotype.Service;

@Service
public class AgentTaskStateMachine {
    private final TaskRepository tasks;
    private final MeterRegistry metrics;

    public AgentTaskStateMachine(TaskRepository tasks, MeterRegistry metrics) {
        this.tasks = tasks;
        this.metrics = metrics;
    }

    public AgentTask transition(AgentTask task, AgentTaskStatus target) {
        AgentTaskStatus source = task.getStatus();
        if (source == target) return heartbeat(task);
        if (!source.canTransitionTo(target)) {
            throw ApiException.conflict(
                    "INVALID_TASK_TRANSITION", "任务不能从 " + source + " 转换到 " + target);
        }
        task.setStatus(target);
        metrics.counter(
                        "heartpilot.agent.task.transitions",
                        "from",
                        source.name(),
                        "to",
                        target.name())
                .increment();
        task.setHeartbeatAt(Instant.now());
        if (target == AgentTaskStatus.RUNNING) {
            task.setLastStartedAt(Instant.now());
            task.setNextRetryAt(null);
        }
        AgentTask saved = tasks.saveAndFlush(task);
        task.setLockVersion(saved.getLockVersion());
        return saved;
    }

    public AgentTask heartbeat(AgentTask task) {
        task.setHeartbeatAt(Instant.now());
        AgentTask saved = tasks.saveAndFlush(task);
        task.setLockVersion(saved.getLockVersion());
        return saved;
    }
}
