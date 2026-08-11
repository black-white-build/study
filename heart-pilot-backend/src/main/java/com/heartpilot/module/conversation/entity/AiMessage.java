package com.heartpilot.module.conversation.entity;

import com.heartpilot.common.entity.BaseEntity;
import com.heartpilot.module.conversation.entity.enums.AiMessageStatus;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(
        name = "ai_message",
        indexes = {@Index(name = "idx_msg_conversation", columnList = "conversationId,createdAt")})
@Getter
@Setter
@NoArgsConstructor
public class AiMessage extends BaseEntity {
    @Column(nullable = false)
    private Long conversationId;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false, length = 16)
    private String role;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(nullable = false)
    private int inputTokens;

    @Column(nullable = false)
    private int outputTokens;

    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private AiMessageStatus status = AiMessageStatus.COMPLETED;

    @Column(length = 80)
    private String model;

    @Column(length = 500)
    private String errorMessage;

    @Column(length = 1000)
    private String sourcesJson;

    private Long regeneratedFromId;

    @Column(nullable = false)
    private boolean cacheHit;

    private Long providerLatencyMs;

    @Column(nullable = false)
    private long inputCostMicros;

    @Column(nullable = false)
    private long outputCostMicros;

    @Column(nullable = false)
    private long estimatedCostMicros;

    @Column(nullable = false)
    private long cacheSavedCostMicros;
}
