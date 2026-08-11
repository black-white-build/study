package com.heartpilot.module.agent.service.impl;

import com.heartpilot.module.agent.entity.AgentTask;
import com.heartpilot.module.agent.entity.AgentTaskStep;
import com.heartpilot.module.agent.entity.enums.AgentTaskStatus;
import com.heartpilot.module.agent.entity.enums.AgentTaskStepStatus;
import com.heartpilot.module.agent.repository.TaskRepository;
import com.heartpilot.module.agent.repository.TaskStepRepository;
import com.heartpilot.module.agent.service.AgentTaskStepService;
import java.io.IOException;
import java.time.Instant;
import java.util.concurrent.CancellationException;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Applies task-step transitions and keeps task heartbeats in sync with SSE progress events. */
@Service
public class AgentTaskStepServiceImpl implements AgentTaskStepService {
    private final TaskRepository tasks;
    private final TaskStepRepository steps;
    private final AgentTaskStateMachine stateMachine;

    public AgentTaskStepServiceImpl(
            TaskRepository tasks, TaskStepRepository steps, AgentTaskStateMachine stateMachine) {
        this.tasks = tasks;
        this.steps = steps;
        this.stateMachine = stateMachine;
    }

    @Override
    public void complete(AgentTask task, int number, String detail, SseEmitter emitter) {
        assertNotCancelled(task.getId());
        start(task, number, "正在执行…", emitter);
        finish(task, number, detail, emitter);
    }

    @Override
    public void start(AgentTask task, int number, String detail, SseEmitter emitter) {
        assertNotCancelled(task.getId());
        AgentTaskStep step = steps.findByTaskIdAndStepNo(task.getId(), number).orElseThrow();
        step.setStatus(AgentTaskStepStatus.RUNNING);
        step.setDetail(detail);
        step.setStartedAt(Instant.now());
        step.setCompletedAt(null);
        steps.save(step);
        task.setCurrentStep(number);
        if (task.getStatus() != AgentTaskStatus.RUNNING) {
            stateMachine.transition(task, AgentTaskStatus.RUNNING);
        } else {
            stateMachine.heartbeat(task);
        }
        event(emitter, step);
    }

    @Override
    public void finish(AgentTask task, int number, String detail, SseEmitter emitter) {
        AgentTaskStep step = steps.findByTaskIdAndStepNo(task.getId(), number).orElseThrow();
        step.setStatus(AgentTaskStepStatus.COMPLETED);
        step.setDetail(detail);
        step.setCompletedAt(Instant.now());
        steps.save(step);
        task.setCurrentStep(number);
        stateMachine.heartbeat(task);
        event(emitter, step);
    }

    @Override
    public void reset(Long taskId) {
        for (AgentTaskStep step : steps.findByTaskIdOrderByStepNoAsc(taskId)) {
            step.setStatus(AgentTaskStepStatus.PENDING);
            step.setDetail(null);
            step.setStartedAt(null);
            step.setCompletedAt(null);
            step.setRetryCount(step.getRetryCount() + 1);
            steps.save(step);
        }
    }

    private void assertNotCancelled(Long taskId) {
        AgentTask latest = tasks.findById(taskId).orElseThrow();
        if (latest.isCancelRequested()
                || latest.getStatus() == AgentTaskStatus.CANCELLED
                || Thread.currentThread().isInterrupted()) {
            throw new CancellationException("任务已取消");
        }
    }

    private void event(SseEmitter emitter, AgentTaskStep step) {
        try {
            emitter.send(SseEmitter.event().name("step").data(step));
        } catch (IOException ignored) {
            // Task state remains persisted even if the browser disconnects.
        }
    }
}
