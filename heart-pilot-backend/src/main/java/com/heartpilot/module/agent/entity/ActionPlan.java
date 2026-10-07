package com.heartpilot.module.agent.entity;

import com.heartpilot.common.entity.BaseEntity;
import com.heartpilot.module.agent.entity.enums.ActionPlanStatus;
import com.heartpilot.module.agent.entity.enums.GoalType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDate;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 行动计划实体，对应数据库表 action_plan。
 * 是"计划产物"的聚合根：一个任务（agent_task）对应一个行动计划（1:1），
 * 记录用户最终得到的计划的目标与整体状态；每次重新规划产生的具体内容
 * 存放在其下的 plan_version / plan_action_item 中。
 *
 * 与 AgentTask 的职责边界：
 * - AgentTask：一次智能体执行过程（状态机、步骤、可靠性字段）
 * - ActionPlan：用户最终得到的计划（产物），可跨任务版本累积历史
 */
@Entity
@Table(
        name = "action_plan",
        indexes = @Index(name = "idx_action_plan_user", columnList = "userId,createdAt"),
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_action_plan_task",
                        columnNames = {"taskId"}))
@Getter
@Setter
@NoArgsConstructor
public class ActionPlan extends BaseEntity {
    /** 所属 Agent 任务 ID（1:1） */
    @Column(nullable = false)
    private Long taskId;

    /** 计划所属用户 ID */
    @Column(nullable = false)
    private Long userId;

    /** 计划标题，取自任务标题 */
    @Column(nullable = false, length = 140)
    private String title;

    /** 计划目标/需求描述，与任务 objective 一致 */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String objective;

    /** 行动目标类型（该计划整体想达到什么） */
    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private GoalType goalType;

    /** 计划整体状态：草稿 / 已确认 / 已归档 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ActionPlanStatus status = ActionPlanStatus.DRAFT;

    /** 计划开始日期，默认创建当天 */
    @Column(nullable = false)
    private LocalDate startDate = LocalDate.now();

    /** 计划结束日期，默认创建当天，确认后可由业务更新 */
    @Column(nullable = false)
    private LocalDate endDate = LocalDate.now();
}
