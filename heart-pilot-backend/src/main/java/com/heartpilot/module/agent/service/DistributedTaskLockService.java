package com.heartpilot.module.agent.service;

import java.time.Duration;

/**
 * 分布式任务锁服务。 保证在多实例部署下，同一个任务在同一时刻只有一个执行器（线程/节点）在跑， 避免重复执行、并发写覆盖。底层通常基于 Redis 实现。
 *
 * <p>锁语义： - 加锁：tryAcquire 尝试在租期（lease）内持有锁，抢锁失败返回 null； - 续租：执行时间可能超过初始租期，持锁期间可调用 renew
 * 续期，防止业务未完成锁就过期被他人抢走； - 超时：租期到期未续租则锁自动释放（fail-safe），避免持锁进程崩溃后任务永久卡死； - 释放：通过 LockHandle.close()
 * 显式释放（通常放在 finally），释放时校验持有者身份，防止误删他人锁。
 */
public interface DistributedTaskLockService {
    /**
     * 尝试获取任务的分布式锁。
     *
     * @param taskId 任务 ID
     * @param lease 初始租期（锁的过期时间）
     * @return 锁句柄；获取失败（已被他人持有）返回 null
     */
    LockHandle tryAcquire(Long taskId, Duration lease);

    /** 锁句柄，代表一次持锁会话。实现 AutoCloseable，便于 try-with-resources / finally 释放。 */
    interface LockHandle extends AutoCloseable {
        /**
         * 续租：在原锁即将到期前延长持有时间。
         *
         * @return true=续租成功；false=锁已失效（可能已被他人抢走），调用方应中止执行
         */
        boolean renew();

        /** 释放锁。必须幂等：重复调用不报错。释放时仅当自己仍是持有者才删除 key。 */
        @Override
        void close();
    }
}
