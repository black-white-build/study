package com.heartpilot.module.agent.entity;

import com.heartpilot.common.entity.BaseEntity;
import com.heartpilot.module.agent.entity.enums.AgentExecutionEventStatus;
import com.heartpilot.module.agent.entity.enums.AgentExecutionEventType;
import com.heartpilot.module.agent.entity.enums.AgentExecutionPhase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Agent 执行事件实体，对应数据库表 agent_execution_event。
 * 以事件流（Event Sourcing）方式记录任务执行过程中的每一步关键动作：
 * 思考、行动、观察、结果、告警、错误等，用于前端回放执行轨迹与问题排查。
 * 按 taskId + createdAt 建索引，便于按时间线拉取。
 */
@Entity
@Table(
        name = "agent_execution_event",
        indexes = @Index(name = "idx_execution_event_task", columnList = "taskId,createdAt"))
@Getter
@Setter
@NoArgsConstructor
public class AgentExecutionEvent extends BaseEntity {
    /** 所属任务 ID */
    @Column(nullable = false)
    private Long taskId;

    /** 事件发生时的任务版本号，区分重规划前后的执行轨迹 */
    @Column(nullable = false, columnDefinition = "INTEGER DEFAULT 0")
    private int taskVersion;

    /** 关联的步骤编号，全局事件（如任务开始/结束）可为空 */
    private Integer stepNo;

    /** 执行阶段（分析/检索/筛选/路线/生成/完成） */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private AgentExecutionPhase phase;

    /** 事件类型（思考/行动/观察/结果/告警/错误） */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private AgentExecutionEventType eventType;

    /** 事件状态（进行中/成功/失败） */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private AgentExecutionEventStatus status;

    /** 事件标题，简短描述该事件 */
    @Column(nullable = false, length = 160)
    private String title;

    /** 事件详细内容，长文本 */
    @Column(columnDefinition = "TEXT")
    private String detail;

    /** 数据来源提供方（如地图/天气等服务名），可为空 */
    @Column(length = 80)
    private String provider;

    /** 关联的工具名（事件由哪个工具调用产生），可为空 */
    @Column(length = 80)
    private String toolName;

    /** 产出条目数量（如检索到 N 个地点），可为空 */
    private Integer itemCount;

    /** 该事件耗时（毫秒），可为空 */
    private Long durationMs;

    /** 相关来源 URL（如引用的网页/地图链接），可为空 */
    @Column(length = 500)
    private String sourceUrl;

    /** 扩展元数据 JSON，存放结构化附加信息 */
    @Column(columnDefinition = "TEXT")
    private String metadataJson;
}
