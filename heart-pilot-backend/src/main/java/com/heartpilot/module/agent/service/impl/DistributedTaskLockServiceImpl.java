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

/**
 * 基于 Redis 的任务分布式锁实现，保证同一任务在多实例下只有一个执行器。
 *
 * 锁机制（获取 / 续租 / 释放）：
 * - 获取：setIfAbsent（SET NX EX）写入 key=任务锁、value=随机 token，并带过期时间（lease）。
 *   token 随机是为了避免误删别人持有的锁
 * - 续租（renew）：用 Lua 脚本"先比对 token 再 pexpire"，保证只续租自己持有的锁；
 *   脚本原子执行，避免"判断与续期"之间锁被别人抢走
 * - 释放（close）：用 Lua 脚本"先比对 token 再 del"，保证只删自己的锁；
 *   即使释放失败，TTL 也会在 lease 到期后自动释放，不会死锁
 *
 * 降级策略与语义边界（重要）：
 * - Redis 正常时：支持多实例互斥，同一任务在多实例下只有一个执行器；
 * - Redis 未启用或不可用时：退化为 JVM 本地锁（ConcurrentHashMap.newKeySet），
 *   仅保证"单实例内互斥"，多实例部署下同一任务仍可能被不同实例并发执行。
 * 因此整体能力只能表述为"Redis 正常时支持多实例互斥，Redis 不可用时保证单实例内互斥"，
 * 不能笼统写成"Redis 故障也保证分布式任务一致性"。Redis 异常被吞掉不阻断业务，
 * 多实例下的一致性缺口由心跳/恢复扫描 + 状态机 + 乐观锁共同兜底。
 */
@Service
public class DistributedTaskLockServiceImpl implements DistributedTaskLockService {
    /**
     * 释放锁的 Lua 脚本：只有当 key 的值仍等于自己的 token 时才 del，
     * 防止误删其他实例已重新获取的锁。整个脚本原子执行。
     */
    private static final DefaultRedisScript<Long> RELEASE_SCRIPT =
            new DefaultRedisScript<>(
                    "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
                    Long.class);
    /**
     * 续租锁的 Lua 脚本：只有当 key 的值仍等于自己的 token 时才 pexpire 续期，
     * 避免给别人持有的锁续期。
     */
    private static final DefaultRedisScript<Long> RENEW_SCRIPT =
            new DefaultRedisScript<>(
                    "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('pexpire', KEYS[1], ARGV[2]) else return 0 end",
                    Long.class);

    /** Redis 客户端，用 ObjectProvider 延迟获取，未装配 Redis 时为 null */
    private final StringRedisTemplate redis;
    /** 是否启用 Redis 分布式锁，配置项 app.rate-limit.redis-enabled，默认 false */
    private final boolean redisEnabled;
    /** 降级用的 JVM 本地锁集合，Redis 不可用时按 key 互斥 */
    private final Set<String> localLocks = ConcurrentHashMap.newKeySet();

    /**
     * 构造器注入。redis 通过 ObjectProvider 注入，容器没有 Redis 时 getIfAvailable 返回 null。
     */
    public DistributedTaskLockServiceImpl(
            ObjectProvider<StringRedisTemplate> redis,
            @Value("${app.rate-limit.redis-enabled:false}") boolean redisEnabled) {
        this.redis = redis.getIfAvailable();
        this.redisEnabled = redisEnabled;
    }

    /**
     * 尝试获取任务锁。
     * 优先走 Redis（SET NX EX）；Redis 未启用或抛异常时退化为本地锁。
     * @param taskId 任务 ID
     * @param lease 锁租约时长（即 TTL）
     * @return 锁句柄；获取失败返回 null（说明任务正被其他实例/线程持有）
     */
    @Override
    public LockHandle tryAcquire(Long taskId, Duration lease) {
        String key = "heart-pilot:task-lock:" + taskId;
        // 随机 token：释放/续租时用来校验锁持有者，避免误删他人的锁
        String token = UUID.randomUUID().toString();
        if (redisEnabled && redis != null) {
            try {
                // SET NX EX：key 不存在才写入，并设置过期时间
                Boolean acquired = redis.opsForValue().setIfAbsent(key, token, lease);
                if (Boolean.TRUE.equals(acquired))
                    return new LockHandleImpl(key, token, lease, true);
                // 锁已被占用
                return null;
            } catch (RuntimeException ignored) {
                // Redis unavailable: fall back to the JVM-local lock. This guarantees
                // per-instance mutual exclusion ONLY — across instances, the same task
                // may still be executed concurrently while Redis is down. Availability
                // is kept intact at the cost of cross-instance consistency.
            }
        }
        // 降级到本地锁：add 成功表示抢到
        return localLocks.add(key) ? new LockHandleImpl(key, token, lease, false) : null;
    }

    /**
     * 锁句柄实现，持有 key/token/lease，负责续租与释放。
     * close 用 volatile closed 保证幂等，重复关闭无副作用。
     */
    private final class LockHandleImpl implements LockHandle {
        private final String key;
        private final String token;
        private final Duration lease;
        /** true=Redis 分布式锁；false=JVM 本地锁 */
        private final boolean distributed;
        /** 标记锁是否已释放，volatile 保证多线程可见 */
        private volatile boolean closed;

        private LockHandleImpl(String key, String token, Duration lease, boolean distributed) {
            this.key = key;
            this.token = token;
            this.lease = lease;
            this.distributed = distributed;
        }

        /**
         * 续租：把锁 TTL 重新设为一个 lease。
         * Redis 模式下用 Lua 脚本"比对 token 再 pexpire"，只续租自己持有的锁；
         * 本地模式下只要 key 仍在集合里即视为成功。异常返回 false。
         */
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

        /**
         * 释放锁，幂等。Redis 模式下用 Lua 脚本"比对 token 再 del"；
         * 删除失败也不重试——TTL 会保证锁最终自动释放。
         */
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
