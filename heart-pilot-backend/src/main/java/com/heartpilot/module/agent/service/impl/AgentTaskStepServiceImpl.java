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

/**
 * 任务步骤状态推进服务，并把步骤进度通过 SSE 实时推给前端。 Applies task-step transitions and keeps task heartbeats in sync
 * with SSE progress events.
 *
 * <p>可靠性设计要点： - 每次 start/complete 前都调用 assertNotCancelled：重新查库确认取消标志、 任务状态、线程中断标志，任一为真即抛
 * CancellationException，及时终止长步骤 - 步骤开始时若任务还不是 RUNNING，自动经状态机转入 RUNNING；否则只刷心跳，
 * 让任务心跳随步骤推进持续更新，避免被僵死扫描误判 - SSE 推送失败（浏览器断开）静默忽略，步骤状态仍以数据库为准
 */
@Service
public class AgentTaskStepServiceImpl implements AgentTaskStepService {
    /** 任务 Repository，用于重新查库判断取消状态 */
    private final TaskRepository tasks;

    /** 步骤 Repository */
    private final TaskStepRepository steps;

    /** 任务状态机，统一推进任务主状态与心跳 */
    private final AgentTaskStateMachine stateMachine;

    /** 构造器注入。 */
    public AgentTaskStepServiceImpl(
            TaskRepository tasks, TaskStepRepository steps, AgentTaskStateMachine stateMachine) {
        this.tasks = tasks;
        this.steps = steps;
        this.stateMachine = stateMachine;
    }

    /** 一步完成某步骤：先断言未取消，再 start 后立即 finish（用于不需要中间观察的步骤）。 */
    @Override
    public void complete(AgentTask task, int number, String detail, SseEmitter emitter) {
        assertNotCancelled(task.getId());
        start(task, number, "正在执行…", emitter);
        finish(task, number, detail, emitter);
    }

    /** 把某步骤标记为运行中，并推进任务主状态/心跳、推送 SSE。 */
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
        // 任务首次进入执行时经状态机转 RUNNING；已在 RUNNING 则只刷新心跳
        if (task.getStatus() != AgentTaskStatus.RUNNING) {
            stateMachine.transition(task, AgentTaskStatus.RUNNING);
        } else {
            stateMachine.heartbeat(task);
        }
        event(emitter, step);
    }

    /** 把某步骤标记为已完成，刷新任务心跳并推送 SSE。 */
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

    /** 重置任务全部步骤为待执行，并累加每步重试计数。 用户驳回重规划后调用，让新一轮从第一步重新跑。 */
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

    /** 取消守卫：每次步骤推进前重新查库，发现用户请求取消、任务已 CANCELLED 或当前线程被中断时，抛 CancellationException 终止执行。 */
    private void assertNotCancelled(Long taskId) {
        AgentTask latest = tasks.findById(taskId).orElseThrow();
        if (latest.isCancelRequested()
                || latest.getStatus() == AgentTaskStatus.CANCELLED
                || Thread.currentThread().isInterrupted()) {
            throw new CancellationException("任务已取消");
        }
    }

    /** 向 SSE 推送一个 step 事件；客户端断开时静默忽略，不影响持久化状态 */
    private void event(SseEmitter emitter, AgentTaskStep step) {
        try {
            emitter.send(SseEmitter.event().name("step").data(step));
        } catch (IOException ignored) {
            // Task state remains persisted even if the browser disconnects.
        }
    }
}
