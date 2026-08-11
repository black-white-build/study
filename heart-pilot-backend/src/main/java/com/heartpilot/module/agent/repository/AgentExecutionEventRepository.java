package com.heartpilot.module.agent.repository;

import com.heartpilot.module.agent.entity.AgentExecutionEvent;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentExecutionEventRepository extends JpaRepository<AgentExecutionEvent, Long> {
    List<AgentExecutionEvent> findByTaskIdOrderByCreatedAtAsc(Long taskId);

    void deleteByTaskId(Long taskId);
}
