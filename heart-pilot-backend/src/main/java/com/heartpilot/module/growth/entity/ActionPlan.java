package com.heartpilot.module.growth.entity;

import com.heartpilot.common.entity.BaseEntity;
import jakarta.persistence.*;
import java.time.LocalDate;
import lombok.*;

@Entity
@Table(
        name = "action_plan",
        indexes = @Index(name = "idx_plan_user", columnList = "userId,createdAt"))
@Getter
@Setter
@NoArgsConstructor
public class ActionPlan extends BaseEntity {
    @Column(nullable = false)
    private Long userId;

    private Long taskId;

    @Column(nullable = false, length = 140)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String goal;

    @Column(nullable = false)
    private LocalDate startDate;

    @Column(nullable = false)
    private LocalDate endDate;

    @Column(nullable = false, length = 24)
    private String status = "ACTIVE";

    @Column(columnDefinition = "TEXT")
    private String dailyActionsJson;
}
