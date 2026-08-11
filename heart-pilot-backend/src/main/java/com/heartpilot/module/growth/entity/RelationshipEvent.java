package com.heartpilot.module.growth.entity;

import com.heartpilot.common.entity.BaseEntity;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.*;

@Entity
@Table(
        name = "relationship_event",
        indexes = @Index(name = "idx_event_user", columnList = "userId,happenedAt"))
@Getter
@Setter
@NoArgsConstructor
public class RelationshipEvent extends BaseEntity {
    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false, length = 120)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(length = 32)
    private String emotion;

    @Column(nullable = false)
    private Instant happenedAt;
}
