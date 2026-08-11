package com.heartpilot.module.growth.entity;

import com.heartpilot.common.entity.BaseEntity;
import jakarta.persistence.*;
import java.time.LocalDate;
import lombok.*;

@Entity
@Table(
        name = "action_checkin",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_checkin_plan_date",
                        columnNames = {"planId", "checkinDate"}))
@Getter
@Setter
@NoArgsConstructor
public class ActionCheckin extends BaseEntity {
    @Column(nullable = false)
    private Long planId;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private LocalDate checkinDate;

    @Column(nullable = false)
    private boolean completed;

    @Column(length = 32)
    private String emotion;

    @Column(length = 1000)
    private String note;
}
