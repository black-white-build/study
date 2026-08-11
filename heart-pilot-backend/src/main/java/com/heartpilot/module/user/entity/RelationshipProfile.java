package com.heartpilot.module.user.entity;

import com.heartpilot.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(
        name = "relationship_profile",
        uniqueConstraints = @UniqueConstraint(name = "uk_profile_user", columnNames = "userId"))
@Getter
@Setter
@NoArgsConstructor
public class RelationshipProfile extends BaseEntity {
    @Column(nullable = false)
    private Long userId;

    @Column(length = 32)
    private String relationshipStatus = "未设置";

    private Integer relationshipMonths;

    @Column(length = 200)
    private String communicationStyle;

    @Column(length = 1000)
    private String concerns;

    @Column(length = 1000)
    private String preferences;

    @Column(length = 1000)
    private String boundaries;
}
