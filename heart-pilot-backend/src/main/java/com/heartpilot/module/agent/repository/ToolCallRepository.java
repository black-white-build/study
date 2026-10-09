package com.heartpilot.module.agent.repository;

import com.heartpilot.module.agent.entity.ToolCallRecord;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** 工具调用记录 Repository，操作 tool_call_record 表。 */
public interface ToolCallRepository extends JpaRepository<ToolCallRecord, Long> {
    /** 按任务查询工具调用记录，按创建时间正序 */
    List<ToolCallRecord> findByTaskIdOrderByCreatedAtAsc(Long taskId);

    /** 按幂等键查询，用于判断工具调用是否已执行过，防止重复调用 */
    Optional<ToolCallRecord> findByIdempotencyKey(String idempotencyKey);

    /** 删除任务时级联清理其全部工具调用记录 */
    void deleteByTaskId(Long taskId);
}
