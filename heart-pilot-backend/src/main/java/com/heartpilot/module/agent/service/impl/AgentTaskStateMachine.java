package com.heartpilot.module.agent.service.impl;

import com.heartpilot.common.exception.ApiException;
import com.heartpilot.module.agent.entity.AgentTask;
import com.heartpilot.module.agent.entity.enums.AgentTaskStatus;
import com.heartpilot.module.agent.repository.TaskRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import org.springframework.stereotype.Service;

/**
 * Agent 任务状态机。
 * 是全系统唯一允许修改任务 status 的地方，业务代码一律通过 transition() 流转状态，
 * 禁止直接 setStatus，以此集中管理合法的状态迁移规则。
 *
 * 状态流转规则由 {@link AgentTaskStatus#canTransitionTo} 定义，非法迁移直接抛冲突异常；
 * 每次合法流转都会：
 * - 记录 Micrometer 计数器（from/to 标签），便于监控任务状态分布
 * - 刷新心跳时间（heartbeatAt），供僵死任务扫描
 * - 进入 RUNNING 时记录开始时间并清空下次重试时间
 * 保存后同步乐观锁版本号到内存对象，避免后续更新版本冲突。
 */
@Service
public class AgentTaskStateMachine {
    /** 任务 Repository */
    private final TaskRepository tasks;
    /** 指标注册中心，统计状态迁移次数 */
    private final MeterRegistry metrics;

    /**
     * 构造器注入。
     */
    public AgentTaskStateMachine(TaskRepository tasks, MeterRegistry metrics) {
        this.tasks = tasks;
        this.metrics = metrics;
    }

    /**
     * 执行一次状态迁移。
     *
     * 边界处理：
     * - 源状态 == 目标状态：视为一次心跳刷新，不记迁移指标
     * - 源 → 目标不在白名单内：抛 INVALID_TASK_TRANSITION 冲突异常
     * - 迁移到 RUNNING：记录 lastStartedAt 并清空 nextRetryAt
     *
     * @param task 当前任务（内存对象，状态会被就地修改）
     * @param target 目标状态
     * @return 保存后的任务实体（含最新乐观锁版本号）
     */
    public AgentTask transition(AgentTask task, AgentTaskStatus target) {
        AgentTaskStatus source = task.getStatus();
        // 同状态重复调用：只刷新心跳，避免误记一次迁移
        if (source == target) return heartbeat(task);
        // 校验迁移合法性，非法迁移直接拒绝
        if (!source.canTransitionTo(target)) {
            throw ApiException.conflict(
                    "INVALID_TASK_TRANSITION", "任务不能从 " + source + " 转换到 " + target);
        }
        task.setStatus(target);
        // 记录状态迁移指标，按 from/to 打标签
        metrics.counter(
                        "heartpilot.agent.task.transitions",
                        "from",
                        source.name(),
                        "to",
                        target.name())
                .increment();
        task.setHeartbeatAt(Instant.now());
        // 正式开始执行时记录起始时间，并清除待重试时间
        if (target == AgentTaskStatus.RUNNING) {
            task.setLastStartedAt(Instant.now());
            task.setNextRetryAt(null);
        }
        AgentTask saved = tasks.saveAndFlush(task);
        // 回写乐观锁版本号，防止后续更新因版本不匹配失败
        task.setLockVersion(saved.getLockVersion());
        return saved;
    }

    /**
     * 仅刷新任务心跳时间，不改变状态。
     * 长步骤执行期间周期性调用，告诉恢复扫描器"任务还活着"，避免被误判为僵死。
     */
    public AgentTask heartbeat(AgentTask task) {
        task.setHeartbeatAt(Instant.now());
        AgentTask saved = tasks.saveAndFlush(task);
        task.setLockVersion(saved.getLockVersion());
        return saved;
    }
}
