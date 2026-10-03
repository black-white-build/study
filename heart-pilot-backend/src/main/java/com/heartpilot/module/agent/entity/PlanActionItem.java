package com.heartpilot.module.agent.entity;

import com.heartpilot.common.entity.BaseEntity;
import com.heartpilot.module.agent.entity.enums.ActionItemStatus;
import com.heartpilot.module.agent.entity.enums.ExecutionKind;
import com.heartpilot.module.agent.entity.enums.GoalType;
import com.heartpilot.module.agent.entity.enums.RiskLevel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 计划行动条目实体，对应数据库表 plan_action_item。
 * 一个计划版本下包含多条行动条目，每条行动同时具备：
 * - executionKind（执行方式）：地点/消息/沟通/表达/自我练习/观察
 * - goalType（行动目标）：连接/修复/边界/庆祝/决定/自我成长
 * - payloadJson：该类型行动的结构化数据（地点信息、消息草稿、沟通脚本等），
 *   第一版用 JSON 存储，避免为每种行动建一张表
 * - status：逐条完成状态，支撑"我的计划"页面的完成情况展示
 */
@Entity
@Table(
        name = "plan_action_item",
        indexes = {
            @Index(name = "idx_plan_item_plan", columnList = "planId,sequenceNo"),
            @Index(name = "idx_plan_item_version", columnList = "versionId")
        })
@Getter
@Setter
@NoArgsConstructor
public class PlanActionItem extends BaseEntity {
    /** 所属行动计划 ID（冗余，便于按计划直接查询） */
    @Column(nullable = false)
    private Long planId;

    /** 所属计划版本 ID（条目的真正归属） */
    @Column(nullable = false)
    private Long versionId;

    /** 条目顺序号（从 1 开始），用户可在预览页调整顺序 */
    @Column(nullable = false)
    private int sequenceNo;

    /** 行动标题 */
    @Column(nullable = false, length = 200)
    private String title;

    /** 执行方式 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ExecutionKind executionKind;

    /** 行动目标类型 */
    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private GoalType goalType;

    /** 行动说明：怎么做、注意什么 */
    @Column(columnDefinition = "TEXT")
    private String instruction;

    /** 为什么做这一步（理由） */
    @Column(columnDefinition = "TEXT")
    private String rationale;

    /** 时机建议（如"周五晚饭后"） */
    @Column(length = 500)
    private String timingSuggestion;

    /** 建议执行日期（可选） */
    private Instant dueAt;

    /** 预计耗时（分钟） */
    private Integer estimatedDurationMinutes;

    /** 预计花费（元） */
    private BigDecimal estimatedCost;

    /** 风险评估：低/中/高，由规划安全检查评估 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private RiskLevel riskLevel = RiskLevel.LOW;

    /** 是否需要用户额外确认后才执行 */
    @Column(nullable = false)
    private boolean requiresConfirmation;

    /** 逐条完成状态 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ActionItemStatus status = ActionItemStatus.PENDING;

    /** 该类型行动的结构化数据（JSON，如地点/消息草稿/沟通脚本/练习/观察） */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String payloadJson = "{}";

    /** 引用来源列表（JSON 字符串数组，如地图链接、网页来源） */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String sourceReferencesJson = "[]";
}
