package com.heartpilot.domain;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.*;

@Entity
@Table(
        name = "emotion_report",
        indexes = @Index(name = "idx_report_user", columnList = "userId,createdAt"))
@Getter
@Setter
@NoArgsConstructor
public class EmotionReport extends BaseEntity {
    @Column(nullable = false)
    private Long userId;

    private Long conversationId;

    @Column(nullable = false, length = 160)
    private String title;

    @Column(nullable = false, length = 32)
    private String reportType = "RELATIONSHIP_ANALYSIS";

    @Column(columnDefinition = "TEXT")
    private String problemSummary;

    @Column(length = 64)
    private String relationshipStatus;

    @Column(length = 64)
    private String conflictType;

    @Column(length = 16)
    private String riskLevel;

    @Column(columnDefinition = "TEXT")
    private String analysis;

    @Column(columnDefinition = "TEXT")
    private String actionsJson;

    private Instant reviewAt;
    private Long generatedFileId;
}
