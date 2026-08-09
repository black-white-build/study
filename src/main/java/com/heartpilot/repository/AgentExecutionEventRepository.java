package com.heartpilot.repository;

import com.heartpilot.domain.AgentExecutionEvent;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentExecutionEventRepository extends JpaRepository<AgentExecutionEvent, Long> {
    List<AgentExecutionEvent> findByTaskIdOrderByCreatedAtAsc(Long taskId);

    void deleteByTaskId(Long taskId);
}
