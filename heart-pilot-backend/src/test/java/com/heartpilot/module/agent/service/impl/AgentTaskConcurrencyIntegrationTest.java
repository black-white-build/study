package com.heartpilot.module.agent.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.heartpilot.module.agent.entity.AgentTask;
import com.heartpilot.module.agent.repository.TaskRepository;
import com.heartpilot.module.agent.service.AgentTaskService;
import com.heartpilot.module.user.entity.AppUser;
import com.heartpilot.module.user.repository.AppUserRepository;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class AgentTaskConcurrencyIntegrationTest {
    @Autowired AgentTaskService service;
    @Autowired AppUserRepository users;
    @Autowired TaskRepository tasks;

    @Test
    void concurrentRequestsWithSameIdempotencyKeyCreateOneTask() throws Exception {
        AppUser user = new AppUser();
        user.setUsername("idem-" + System.nanoTime());
        user.setPasswordHash("not-used");
        user.setNickname("并发测试");
        user = users.save(user);
        Long userId = user.getId();
        String key = "concurrent-" + System.nanoTime();
        CountDownLatch start = new CountDownLatch(1);
        Callable<AgentTask> request =
                () -> {
                    start.await();
                    return service.create(
                            userId,
                            "并发幂等计划",
                            "验证重复提交",
                            Map.of(
                                    "province",
                                    "广西壮族自治区",
                                    "city",
                                    "南宁市",
                                    "budget",
                                    300,
                                    "questions",
                                    List.of("如何安排沟通步骤")),
                            key);
                };
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(request);
            var second = executor.submit(request);
            start.countDown();
            assertEquals(first.get().getId(), second.get().getId());
        }
        assertEquals(
                1,
                tasks.findByUserId(userId, org.springframework.data.domain.Pageable.unpaged())
                        .getTotalElements());
    }
}
