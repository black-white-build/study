package com.heartpilot.module.agent.repository;

import com.heartpilot.module.agent.entity.ActionPlan;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 行动计划（ActionPlan）数据访问层。
 * 一个任务对应一个计划（task_id 唯一约束），按任务/用户查询。
 */
public interface ActionPlanRepository extends JpaRepository<ActionPlan, Long> {
    /** 按任务 ID 查询计划（一个任务至多一个） */
    Optional<ActionPlan> findByTaskId(Long taskId);

    /** 分页查询某用户的计划列表，按创建时间倒序 */
    List<ActionPlan> findByUserIdOrderByCreatedAtDesc(Long userId);

    /** 删除任务时级联清理其计划 */
    void deleteByTaskId(Long taskId);
}
