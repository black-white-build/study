package com.heartpilot.module.agent.requirement;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** 结构化需求状态表的 JPA 仓库。 */
public interface AgentRequirementStateRepository
        extends JpaRepository<AgentRequirementState, Long> {
    /** 按任务查询唯一一条结构化需求状态。 */
    Optional<AgentRequirementState> findByTaskId(Long taskId);

    /** 删除任务的结构化需求状态（任务删除时级联清理）。 */
    void deleteByTaskId(Long taskId);
}
