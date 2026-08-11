package com.heartpilot.module.conversation.entity;

import com.heartpilot.common.entity.BaseEntity;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.*;

@Entity
@Table(
        name = "ai_conversation",
        indexes = {@Index(name = "idx_conv_user_updated", columnList = "userId,updatedAt")})
@Getter
@Setter
@NoArgsConstructor
public class AiConversation extends BaseEntity {
    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false, length = 120)
    private String title;

    @Column(nullable = false, length = 40)
    private String model = "qwen-plus";

    @Column(nullable = false)
    private int contextLimit = 20;

    @Column(nullable = false)
    private boolean archived = false;

    private Instant lastMessageAt;
}
