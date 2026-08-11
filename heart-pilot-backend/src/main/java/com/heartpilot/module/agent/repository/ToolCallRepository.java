package com.heartpilot.module.agent.repository;

import com.heartpilot.module.agent.entity.ToolCallRecord;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ToolCallRepository extends JpaRepository<ToolCallRecord, Long> {
    List<ToolCallRecord> findByTaskIdOrderByCreatedAtAsc(Long taskId);

    Optional<ToolCallRecord> findByIdempotencyKey(String idempotencyKey);

    void deleteByTaskId(Long taskId);
}
