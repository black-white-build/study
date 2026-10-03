package com.heartpilot.module.agent.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.heartpilot.common.exception.ApiException;
import com.heartpilot.module.agent.entity.AgentTask;
import com.heartpilot.module.agent.entity.enums.AgentTaskStatus;
import com.heartpilot.module.agent.service.AgentTaskService;
import com.heartpilot.module.user.entity.AppUser;
import com.heartpilot.module.user.repository.AppUserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 取消竞态集成测试（P12）。
 * 验证"用户取消后不能被覆盖成成功"：
 * 任务进入等待确认后取消 → 状态置为 CANCELLED；此后即使调用确认接口
 * 也会被状态守卫拒绝，最终状态保持 CANCELLED，不会被覆盖为 SUCCEEDED。
 */
@SpringBootTest
class AgentTaskCancelRaceIntegrationTest {
    @Autowired AgentTaskService tasks;
    @Autowired AppUserRepository users;

    @Test
    void cancelledTaskCannotBeOverwrittenToSucceeded() throws Exception {
        AppUser user = new AppUser();
        user.setUsername("cancel-race-" + System.nanoTime());
        user.setPasswordHash("not-used-in-this-test");
        user.setNickname("取消竞态");
        users.save(user);

        AgentTask task =
                tasks.create(
                        user.getId(),
                        "取消竞态测试",
                        "在南宁安排一次安静散步",
                        Map.of(
                                "province",
                                "广西壮族自治区",
                                "city",
                                "南宁市",
                                "budget",
                                300,
                                "questions",
                                List.of("安静散步")),
                        "cancel-race-" + System.nanoTime());

        tasks.run(task.getId(), user.getId());
        // 等待进入确认阶段
        Instant deadline = Instant.now().plus(Duration.ofSeconds(8));
        AgentTaskService.TaskDetail detail;
        do {
            Thread.sleep(50);
            detail = tasks.get(task.getId(), user.getId());
        } while (detail.task().getStatus() != AgentTaskStatus.AWAITING_CONFIRMATION
                && !detail.task().getStatus().isTerminal()
                && Instant.now().isBefore(deadline));
        assertEquals(AgentTaskStatus.AWAITING_CONFIRMATION, detail.task().getStatus());

        // 用户取消
        tasks.cancel(task.getId(), user.getId());
        assertEquals(AgentTaskStatus.CANCELLED, tasks.get(task.getId(), user.getId()).task().getStatus());

        // 取消后再次确认：状态守卫拒绝，绝不能变成 SUCCEEDED
        assertThrows(
                ApiException.class,
                () ->
                        tasks.confirm(
                                task.getId(),
                                user.getId(),
                                true,
                                "",
                                "广西壮族自治区",
                                "南宁市",
                                null,
                                List.of()));
        AgentTaskStatus afterConfirm =
                tasks.get(task.getId(), user.getId()).task().getStatus();
        assertEquals(AgentTaskStatus.CANCELLED, afterConfirm, "取消后确认不得把状态覆盖为成功");
    }
}
