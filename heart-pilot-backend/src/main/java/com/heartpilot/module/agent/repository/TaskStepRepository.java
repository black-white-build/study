package com.heartpilot.module.agent.repository;

import com.heartpilot.module.agent.entity.AgentTaskStep;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

/** 任务步骤 Repository，操作 agent_task_step 表。 */
public interface TaskStepRepository extends JpaRepository<AgentTaskStep, Long> {
    /** 按任务查询全部步骤，按步骤编号正序 */
    List<AgentTaskStep> findByTaskIdOrderByStepNoAsc(Long taskId);

    /** 按任务 + 步骤编号定位单一步骤 */
    Optional<AgentTaskStep> findByTaskIdAndStepNo(Long taskId, int stepNo);

    /** 删除任务时级联清理其全部步骤 */
    void deleteByTaskId(Long taskId);
}
