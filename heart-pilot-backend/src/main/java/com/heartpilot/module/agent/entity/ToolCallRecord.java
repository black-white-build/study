package com.heartpilot.module.agent.entity;

import com.heartpilot.common.entity.BaseEntity;
import com.heartpilot.module.agent.entity.enums.ToolCallStatus;
import jakarta.persistence.*;
import lombok.*;

/**
 * 工具调用记录实体，对应数据库表 tool_call_record。
 * 记录 Agent 在执行过程中对外部工具/能力（检索、地图、天气等）的每一次调用，
 * 含入参、结果摘要、耗时、状态与幂等键，用于审计、排障与幂等控制。
 * idempotencyKey 唯一约束保证同一工具调用不会因重试而重复执行。
 */
@Entity
@Table(
        name = "tool_call_record",
        indexes = @Index(name = "idx_tool_task", columnList = "taskId,createdAt"))
@Getter
@Setter
@NoArgsConstructor
public class ToolCallRecord extends BaseEntity {
    /** 所属任务 ID */
    @Column(nullable = false)
    private Long taskId;

    /** 所属步骤 ID，可为空（非步骤内的调用） */
    private Long stepId;

    /** 工具名称 */
    @Column(nullable = false, length = 80)
    private String toolName;

    /** 调用入参的 JSON */
    @Column(columnDefinition = "TEXT")
    private String argumentsJson;

    /** 调用结果摘要（截断存储，避免大结果撑爆字段） */
    @Column(columnDefinition = "TEXT")
    private String resultSummary;

    /** 调用状态（进行中/成功/失败/超时/取消） */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private ToolCallStatus status;

    /** 调用耗时（毫秒） */
    private Long durationMs;

    /** 失败时的错误信息 */
    @Column(length = 500)
    private String errorMessage;

    /** 幂等键，唯一索引，防止同一次调用被重复执行 */
    @Column(length = 80, unique = true)
    private String idempotencyKey;
}
