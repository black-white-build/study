package com.heartpilot.service;

import com.heartpilot.domain.AgentTask;
import com.heartpilot.domain.enums.AgentTaskStatus;
import com.heartpilot.repository.TaskRepository;
import com.heartpilot.web.ApiException;
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
