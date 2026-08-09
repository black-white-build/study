package com.heartpilot.domain;

import com.heartpilot.domain.enums.AgentTaskStatus;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.*;

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
    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false, length = 140)
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String objective;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private AgentTaskStatus status = AgentTaskStatus.WAITING;

    @Column(columnDefinition = "TEXT")
    private String parametersJson;

    @Column(columnDefinition = "TEXT")
    private String planPreview;

    @Column(columnDefinition = "TEXT")
    private String finalResult;

    @Column(columnDefinition = "TEXT")
    private String journeyEvidenceJson;

    @Column(columnDefinition = "TEXT")
    private String ambienceImagesJson;

    private Instant evidenceUpdatedAt;

    @Column(nullable = false)
    private int currentStep;

    @Column(nullable = false)
    private int maxSteps = 10;

    @Column(nullable = false)
    private boolean cancelRequested;

    @Column(nullable = false)
    private int versionNo;

    private Integer retryCount = 0;

    private Integer maxRetries = 2;

    private Instant heartbeatAt;
    private Instant lastStartedAt;
    private Instant nextRetryAt;

    @Column(length = 96)
    private String requestIdempotencyKey;

    @Column(length = 500)
    private String errorMessage;

    @Version private Long lockVersion;

    @PrePersist
    @PostLoad
    void applyReliabilityDefaults() {
        if (retryCount == null) retryCount = 0;
        if (maxRetries == null) maxRetries = 2;
    }
}
