package com.heartpilot.repository;

import com.heartpilot.domain.AgentTaskStep;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaskStepRepository extends JpaRepository<AgentTaskStep, Long> {
    List<AgentTaskStep> findByTaskIdOrderByStepNoAsc(Long taskId);

    Optional<AgentTaskStep> findByTaskIdAndStepNo(Long taskId, int stepNo);

    void deleteByTaskId(Long taskId);
}
