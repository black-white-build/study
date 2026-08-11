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

@Entity
@Table(
        name = "agent_execution_event",
        indexes = @Index(name = "idx_execution_event_task", columnList = "taskId,createdAt"))
@Getter
@Setter
@NoArgsConstructor
public class AgentExecutionEvent extends BaseEntity {
    @Column(nullable = false)
    private Long taskId;

    @Column(nullable = false, columnDefinition = "INTEGER DEFAULT 0")
    private int taskVersion;

    private Integer stepNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private AgentExecutionPhase phase;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private AgentExecutionEventType eventType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private AgentExecutionEventStatus status;

    @Column(nullable = false, length = 160)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String detail;

    @Column(length = 80)
    private String provider;

    @Column(length = 80)
    private String toolName;

    private Integer itemCount;
    private Long durationMs;

    @Column(length = 500)
    private String sourceUrl;

    @Column(columnDefinition = "TEXT")
    private String metadataJson;
}
