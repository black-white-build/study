package com.heartpilot.module.agent.entity;

import com.heartpilot.common.entity.BaseEntity;
import com.heartpilot.module.agent.entity.enums.PlanVersionStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 计划版本实体，对应数据库表 plan_version。
 * 每次重新规划（含用户驳回后的重规划）都会产生一个不可变版本，
 * 旧版本只读保留，用于"我的计划"页面的历史版本查看与回溯。
 * 同一计划下 versionNo 唯一，且与 AgentTask.versionNo 对齐。
 */
@Entity
@Table(
        name = "plan_version",
        indexes = @Index(name = "idx_plan_version_plan", columnList = "planId"),
        uniqueConstraints =
                @UniqueConstraint(name = "uk_plan_version", columnNames = {"planId", "versionNo"}))
@Getter
@Setter
@NoArgsConstructor
public class PlanVersion extends BaseEntity {
    /** 所属行动计划 ID */
    @Column(nullable = false)
    private Long planId;

    /** 版本号，与任务 versionNo 对齐，从 0 开始 */
    @Column(nullable = false)
    private int versionNo;

    /** 版本状态：草稿 / 已确认 / 已取代 / 已驳回 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private PlanVersionStatus status = PlanVersionStatus.DRAFT;

    /** 候选计划预览文本（等待用户确认阶段展示，由行动条目渲染） */
    @Column(columnDefinition = "TEXT")
    private String previewText;

    /** 正式计划全文（用户确认后生成，如最终报告 Markdown） */
    @Column(columnDefinition = "TEXT")
    private String fullText;

    /** 版本备注：驳回原因或用户修改说明 */
    @Column(length = 2000)
    private String note;
}
