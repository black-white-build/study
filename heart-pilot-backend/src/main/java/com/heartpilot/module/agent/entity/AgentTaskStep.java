package com.heartpilot.module.agent.entity;

import com.heartpilot.common.entity.BaseEntity;
import com.heartpilot.module.agent.entity.enums.AgentTaskStepStatus;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.*;

/**
 * Agent 任务步骤实体，对应数据库表 agent_task_step。
 * 一个任务拆分为多个有序执行步骤（分析、检索、生成等），每步有独立状态与重试计数。
 * taskId + stepNo 组成唯一约束，防止同一步骤重复创建。
 */
@Entity
@Table(
        name = "agent_task_step",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_task_step",
                        columnNames = {"taskId", "stepNo"}))
@Getter
@Setter
@NoArgsConstructor
public class AgentTaskStep extends BaseEntity {
    /** 所属任务 ID */
    @Column(nullable = false)
    private Long taskId;

    /** 步骤编号，从 1 开始，同一任务内唯一 */
    @Column(nullable = false)
    private int stepNo;

    /** 步骤名称（如"地点检索""路线规划"） */
    @Column(nullable = false, length = 120)
    private String name;

    /** 步骤状态，默认待处理；状态流转由执行引擎管理 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private AgentTaskStepStatus status = AgentTaskStepStatus.PENDING;

    /** 步骤执行详情/产出说明，长文本 */
    @Column(columnDefinition = "TEXT")
    private String detail;

    /** 步骤开始执行时间 */
    private Instant startedAt;
    /** 步骤完成时间 */
    private Instant completedAt;

    /** 当前步骤已重试次数 */
    @Column(nullable = false)
    private int retryCount;

    /** 是否需要用户确认后才继续执行下一步（如候选计划待确认） */
    @Column(nullable = false)
    private boolean confirmationRequired;
}
