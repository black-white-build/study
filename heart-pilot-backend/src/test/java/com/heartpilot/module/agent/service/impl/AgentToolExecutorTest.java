package com.heartpilot.module.agent.service.impl;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.heartpilot.module.agent.entity.AgentTask;
import com.heartpilot.module.agent.repository.ToolCallRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class AgentToolExecutorTest {
    @Test
    void timeoutInterruptsUnderlyingTool() throws Exception {
        ToolCallRepository repository = mock(ToolCallRepository.class);
        when(repository.findByIdempotencyKey(any())).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        AtomicBoolean interrupted = new AtomicBoolean();
        CountDownLatch started = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            AgentToolExecutor toolExecutor =
                    new AgentToolExecutor(
                            repository,
                            executor,
                            1,
                            1,
                            new SimpleMeterRegistry(),
                            new ObjectMapper());
            AgentTask task = new AgentTask();
            task.setId(3L);

            assertThrows(
                    TimeoutException.class,
                    () ->
                            toolExecutor.execute(
                                    task,
                                    1,
                                    "slow-tool",
                                    "{}",
                                    () -> {
                                        started.countDown();
                                        try {
                                            Thread.sleep(30_000);
                                        } catch (InterruptedException exception) {
                                            interrupted.set(true);
                                            throw exception;
                                        }
                                        return "unexpected";
                                    }));
            assertTrue(started.await(1, TimeUnit.SECONDS));
            for (int i = 0; i < 20 && !interrupted.get(); i++) Thread.sleep(25);
            assertTrue(interrupted.get(), "timed-out tool should receive interruption");
        }
    }
}
