package com.heartpilot.module.agent.requirement;

import com.heartpilot.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 结构化需求持久化实体（对应表 agent_requirement_state）。
 * <p>数据库不只保存对话文本，额外保存解析后的结构化 JSON，
 * 支持增量局部修改约束（PATCH 单条字段直接更新 JSON，不用整段重写 prompt）。
 * 每个任务一条（taskId 唯一），与 agent_task 1:1。
 */
@Entity
@Table(
        name = "agent_requirement_state",
        indexes = @Index(name = "idx_req_state_user", columnList = "userId"),
        uniqueConstraints =
                @UniqueConstraint(name = "uk_req_state_task", columnNames = "taskId"))
@Getter
@Setter
@NoArgsConstructor
public class AgentRequirementState extends BaseEntity {
    /** 所属任务 ID（唯一，1:1） */
    @Column(nullable = false)
    private Long taskId;

    /** 所属用户 ID */
    @Column(nullable = false)
    private Long userId;

    /** 需求类型（PLACE / GIFT） */
    @Column(nullable = false, length = 16)
    private String requirementType;

    /** 结构化需求 JSON（固定 Schema，持久化的约束唯一来源） */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String structuredJson;

    /** 最近一次代码校验结果 JSON（问题列表快照） */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String validationJson = "[]";

    /** 需求状态：DRAFT=待确认（有冲突待处理）；CONFIRMED=用户已确认 */
    @Column(nullable = false, length = 16)
    private String status = "DRAFT";

    /** 抽取来源：AI=大模型抽取；RULE=规则降级抽取 */
    @Column(nullable = false, length = 16)
    private String extractionSource = "AI";
}
