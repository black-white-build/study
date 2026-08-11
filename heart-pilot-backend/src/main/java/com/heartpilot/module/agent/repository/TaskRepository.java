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

public interface TaskRepository extends JpaRepository<AgentTask, Long> {
    Page<AgentTask> findByUserId(Long userId, Pageable pageable);

    Optional<AgentTask> findByIdAndUserId(Long id, Long userId);

    Optional<AgentTask> findByUserIdAndRequestIdempotencyKey(
            Long userId, String requestIdempotencyKey);

    @Query(
            "select task from AgentTask task where task.status = :status "
                    + "and (task.heartbeatAt is null or task.heartbeatAt < :heartbeatBefore)")
    List<AgentTask> findStaleTasks(
            @Param("status") AgentTaskStatus status,
            @Param("heartbeatBefore") Instant heartbeatBefore);

    List<AgentTask> findByStatusAndNextRetryAtBefore(AgentTaskStatus status, Instant retryBefore);
}
