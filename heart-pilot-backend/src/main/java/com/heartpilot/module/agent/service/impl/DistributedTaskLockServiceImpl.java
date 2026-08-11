package com.heartpilot.module.agent.service.impl;

import com.heartpilot.module.agent.service.DistributedTaskLockService;
import java.time.Duration;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

@Service
public class DistributedTaskLockServiceImpl implements DistributedTaskLockService {
    private static final DefaultRedisScript<Long> RELEASE_SCRIPT =
            new DefaultRedisScript<>(
                    "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
                    Long.class);
    private static final DefaultRedisScript<Long> RENEW_SCRIPT =
            new DefaultRedisScript<>(
                    "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('pexpire', KEYS[1], ARGV[2]) else return 0 end",
                    Long.class);

    private final StringRedisTemplate redis;
    private final boolean redisEnabled;
    private final Set<String> localLocks = ConcurrentHashMap.newKeySet();

    public DistributedTaskLockServiceImpl(
            ObjectProvider<StringRedisTemplate> redis,
            @Value("${app.rate-limit.redis-enabled:false}") boolean redisEnabled) {
        this.redis = redis.getIfAvailable();
        this.redisEnabled = redisEnabled;
    }

    @Override
    public LockHandle tryAcquire(Long taskId, Duration lease) {
        String key = "heart-pilot:task-lock:" + taskId;
        String token = UUID.randomUUID().toString();
        if (redisEnabled && redis != null) {
            try {
                Boolean acquired = redis.opsForValue().setIfAbsent(key, token, lease);
                if (Boolean.TRUE.equals(acquired))
                    return new LockHandleImpl(key, token, lease, true);
                return null;
            } catch (RuntimeException ignored) {
                // Redis unavailable: local locking keeps a single instance safe and availability
                // intact.
            }
        }
        return localLocks.add(key) ? new LockHandleImpl(key, token, lease, false) : null;
    }

    private final class LockHandleImpl implements LockHandle {
        private final String key;
        private final String token;
        private final Duration lease;
        private final boolean distributed;
        private volatile boolean closed;

        private LockHandleImpl(String key, String token, Duration lease, boolean distributed) {
            this.key = key;
            this.token = token;
            this.lease = lease;
            this.distributed = distributed;
        }

        @Override
        public boolean renew() {
            if (closed) return false;
            if (!distributed) return localLocks.contains(key);
            try {
                Long result =
                        redis.execute(
                                RENEW_SCRIPT,
                                Collections.singletonList(key),
                                token,
                                String.valueOf(lease.toMillis()));
                return result != null && result == 1L;
            } catch (RuntimeException ignored) {
                return false;
            }
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            if (distributed) {
                try {
                    redis.execute(RELEASE_SCRIPT, Collections.singletonList(key), token);
                } catch (RuntimeException ignored) {
                    // TTL guarantees eventual release.
                }
            } else {
                localLocks.remove(key);
            }
        }
    }
}
