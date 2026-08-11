package com.heartpilot.module.agent.service;

import java.time.Duration;

public interface DistributedTaskLockService {
    LockHandle tryAcquire(Long taskId, Duration lease);

    interface LockHandle extends AutoCloseable {
        boolean renew();

        @Override
        void close();
    }
}
