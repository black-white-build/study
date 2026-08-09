package com.heartpilot.domain;

import com.heartpilot.domain.enums.ToolCallStatus;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(
        name = "tool_call_record",
        indexes = @Index(name = "idx_tool_task", columnList = "taskId,createdAt"))
@Getter
@Setter
@NoArgsConstructor
public class ToolCallRecord extends BaseEntity {
    @Column(nullable = false)
    private Long taskId;

    private Long stepId;

    @Column(nullable = false, length = 80)
    private String toolName;

    @Column(columnDefinition = "TEXT")
    private String argumentsJson;

    @Column(columnDefinition = "TEXT")
    private String resultSummary;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private ToolCallStatus status;

    private Long durationMs;

    @Column(length = 500)
    private String errorMessage;

    @Column(length = 80, unique = true)
    private String idempotencyKey;
}
