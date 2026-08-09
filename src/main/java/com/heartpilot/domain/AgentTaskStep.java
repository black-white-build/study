package com.heartpilot.domain;

import com.heartpilot.domain.enums.AgentTaskStepStatus;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.*;

@Entity
@Table(
        name = "agent_task_step",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_task_step",
                        columnNames = {"taskId", "stepNo"}))
@Getter
@Setter
@NoArgsConstructor
public class AgentTaskStep extends BaseEntity {
    @Column(nullable = false)
    private Long taskId;

    @Column(nullable = false)
    private int stepNo;

    @Column(nullable = false, length = 120)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private AgentTaskStepStatus status = AgentTaskStepStatus.PENDING;

    @Column(columnDefinition = "TEXT")
    private String detail;

    private Instant startedAt;
    private Instant completedAt;

    @Column(nullable = false)
    private int retryCount;

    @Column(nullable = false)
    private boolean confirmationRequired;
}
