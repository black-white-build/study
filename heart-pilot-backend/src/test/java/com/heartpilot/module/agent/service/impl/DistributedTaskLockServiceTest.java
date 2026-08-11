package com.heartpilot.module.agent.service.impl;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

import com.heartpilot.module.agent.service.DistributedTaskLockService;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;

class DistributedTaskLockServiceTest {
    @Test
    void localFallbackPreservesMutualExclusion() {
        @SuppressWarnings("unchecked")
        ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
        DistributedTaskLockService service = new DistributedTaskLockServiceImpl(provider, false);

        DistributedTaskLockService.LockHandle first = service.tryAcquire(9L, Duration.ofSeconds(5));
        assertNotNull(first);
        assertNull(service.tryAcquire(9L, Duration.ofSeconds(5)));
        first.close();
        assertNotNull(service.tryAcquire(9L, Duration.ofSeconds(5)));
    }
}
