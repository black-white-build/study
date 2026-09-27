package com.heartpilot.module.agent.repository;

import com.heartpilot.module.agent.entity.AgentTask;
import com.heartpilot.module.agent.entity.enums.AgentTaskStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Agent 任务 Repository，操作 agent_task 表。
 */
public interface TaskRepository extends JpaRepository<AgentTask, Long> {
    /** 分页查询某用户的任务列表 */
    Page<AgentTask> findByUserId(Long userId, Pageable pageable);

    /** 按 ID + 用户查询，保证只能访问自己的任务 */
    Optional<AgentTask> findByIdAndUserId(Long id, Long userId);

    /** 按用户 + 幂等键查询，用于幂等判断重复投稿 */
    Optional<AgentTask> findByUserIdAndRequestIdempotencyKey(
            Long userId, String requestIdempotencyKey);

    /**
     * 扫描僵死任务：状态为指定状态且心跳为空或早于阈值。
     * 服务宕机恢复后据此重新接管未完成的任务。
     */
    @Query(
            "select task from AgentTask task where task.status = :status "
                    + "and (task.heartbeatAt is null or task.heartbeatAt < :heartbeatBefore)")
    List<AgentTask> findStaleTasks(
            @Param("status") AgentTaskStatus status,
            @Param("heartbeatBefore") Instant heartbeatBefore);

    /** 查询已到重试时间的等待重试任务，供调度器恢复执行 */
    List<AgentTask> findByStatusAndNextRetryAtBefore(AgentTaskStatus status, Instant retryBefore);
}
