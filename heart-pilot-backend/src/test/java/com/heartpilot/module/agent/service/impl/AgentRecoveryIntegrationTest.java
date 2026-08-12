package com.heartpilot.module.agent.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.heartpilot.module.agent.entity.AgentTask;
import com.heartpilot.module.agent.entity.AgentTaskStep;
import com.heartpilot.module.agent.entity.enums.AgentTaskStatus;
import com.heartpilot.module.agent.entity.enums.AgentTaskStepStatus;
import com.heartpilot.module.agent.repository.TaskRepository;
import com.heartpilot.module.agent.repository.TaskStepRepository;
import com.heartpilot.module.agent.service.AgentTaskService;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class AgentRecoveryIntegrationTest {
    @Autowired AgentTaskService service;
    @Autowired TaskRepository tasks;
    @Autowired TaskStepRepository steps;

    @Test
    void staleRunningTaskMovesToRetryWaitAndResetsRunningStep() {
        AgentTask task = new AgentTask();
        task.setUserId(991L);
        task.setTitle("中断恢复测试");
        task.setObjective("验证服务重启后的恢复状态");
        task.setStatus(AgentTaskStatus.RUNNING);
        task.setHeartbeatAt(Instant.now().minusSeconds(300));
        task = tasks.save(task);

        AgentTaskStep step = new AgentTaskStep();
        step.setTaskId(task.getId());
        step.setStepNo(1);
        step.setName("执行中的步骤");
        step.setStatus(AgentTaskStepStatus.RUNNING);
        steps.save(step);

        service.recoverInterruptedTasks();

        AgentTask recovered = tasks.findById(task.getId()).orElseThrow();
        AgentTaskStep recoveredStep = steps.findByTaskIdAndStepNo(task.getId(), 1).orElseThrow();
        assertEquals(AgentTaskStatus.RETRY_WAIT, recovered.getStatus());
        assertEquals(1, recovered.getRetryCount());
        assertEquals(AgentTaskStepStatus.PENDING, recoveredStep.getStatus());
        assertEquals(1, recoveredStep.getRetryCount());
    }
}
