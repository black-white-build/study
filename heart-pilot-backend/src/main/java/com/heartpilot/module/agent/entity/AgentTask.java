package com.heartpilot.module.agent.entity;

import com.heartpilot.common.entity.BaseEntity;
import com.heartpilot.module.agent.entity.enums.AgentTaskStatus;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.*;

/**
 * Agent 任务实体，对应数据库表 agent_task。
 * 一条任务记录了用户从创建行动计划到最终生成报告的完整生命周期，
 * 包含任务状态机、步骤进度、参数快照、重试与心跳等可靠性字段。
 * 继承 BaseEntity 获得 id、创建时间、更新时间等公共字段。
 */
@Entity
@Table(
        name = "agent_task",
        indexes = @Index(name = "idx_task_user", columnList = "userId,createdAt"),
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_task_user_idempotency",
                        columnNames = {"userId", "requestIdempotencyKey"}))
@Getter
@Setter
@NoArgsConstructor
public class AgentTask extends BaseEntity {
    /** 任务所属用户 ID */
    @Column(nullable = false)
    private Long userId;

    /** 任务标题，默认取"城市+行动计划" */
    @Column(nullable = false, length = 140)
    private String title;

    /** 用户输入的目标/需求描述，TEXT 类型支持长文本 */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String objective;

    /**
     * 任务当前状态，使用字符串存储便于可读与扩展。
     * 状态流转由 AgentTaskStateMachine 统一管理，禁止直接 setStatus。
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private AgentTaskStatus status = AgentTaskStatus.WAITING;

    /** 任务输入参数的 JSON 快照（城市、预算、问题列表、修改记录等），每次修订会覆盖更新 */
    @Column(columnDefinition = "TEXT")
    private String parametersJson;

    /** 候选计划预览文本，在等待用户确认阶段展示 */
    @Column(columnDefinition = "TEXT")
    private String planPreview;

    /** 最终生成的行动报告内容（Markdown/结构化文本） */
    @Column(columnDefinition = "TEXT")
    private String finalResult;

    /** 行程检索证据的 JSON 快照（地点、路线等），用于报告生成与溯源 */
    @Column(columnDefinition = "TEXT")
    private String journeyEvidenceJson;

    /** 氛围图相关数据的 JSON 快照 */
    @Column(columnDefinition = "TEXT")
    private String ambienceImagesJson;

    /** 证据最后更新时间，用于判断缓存是否过期 */
    private Instant evidenceUpdatedAt;

    /** 当前执行到的步骤编号（从 1 开始，0 表示尚未开始） */
    @Column(nullable = false)
    private int currentStep;

    /** 任务允许的最大步骤数，防止异常情况下无限循环 */
    @Column(nullable = false)
    private int maxSteps = 10;

    /** 用户是否请求取消任务，执行循环中会定期检查此标志 */
    @Column(nullable = false)
    private boolean cancelRequested;

    /** 任务版本号，每次修订（用户驳回重规划）自增，用于执行轨迹与参数的版本隔离 */
    @Column(nullable = false)
    private int versionNo;

    /** 当前已重试次数，Integer 类型允许 null，由 applyReliabilityDefaults 兜底为 0 */
    private Integer retryCount = 0;

    /** 最大重试次数，超过后任务状态置为 FAILED */
    private Integer maxRetries = 2;

    /** 心跳时间戳，恢复扫描据此判断任务是否因服务宕机而僵死 */
    private Instant heartbeatAt;
    /** 最近一次开始执行的时间 */
    private Instant lastStartedAt;
    /** 下次允许重试的时间，实现指数退避 */
    private Instant nextRetryAt;

    /**
     * 幂等键，同一用户 + 同一幂等键的重复请求只会创建一条任务。
     * 与 userId 组成唯一索引，防止网络重试导致重复投稿。
     */
    @Column(length = 96)
    private String requestIdempotencyKey;

    /** 最近一次失败的错误信息，截断存储便于前端展示 */
    @Column(length = 500)
    private String errorMessage;

    /** JPA 乐观锁版本号，并发更新（如恢复扫描与正常执行）时防止覆盖丢失 */
    @Version private Long lockVersion;

    /**
     * 实体持久化前和加载后统一兜底可靠性字段的默认值。
     * 因为 retryCount / maxRetries 是 Integer 可为 null，
     * 旧数据或手动构造的实例可能缺少值，在此统一初始化为 0 / 2。
     */
    @PrePersist
    @PostLoad
    void applyReliabilityDefaults() {
        if (retryCount == null) retryCount = 0;
        if (maxRetries == null) maxRetries = 2;
    }
}
