package com.heartpilot.module.agent.repository;

import com.heartpilot.module.agent.entity.AgentExecutionEvent;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** 执行事件 Repository，操作 agent_execution_event 表。 */
public interface AgentExecutionEventRepository extends JpaRepository<AgentExecutionEvent, Long> {
    /** 按任务查询事件时间线，按创建时间正序 */
    List<AgentExecutionEvent> findByTaskIdOrderByCreatedAtAsc(Long taskId);

    /** 删除任务时级联清理其全部事件 */
    void deleteByTaskId(Long taskId);
}
