package com.heartpilot.domain.enums;

import java.util.EnumSet;
import java.util.Set;

public enum AgentTaskStatus {
    WAITING,
    RUNNING,
    AWAITING_CONFIRMATION,
    RETRY_WAIT,
    SUCCEEDED,
    FAILED,
    CANCELLED;

    public boolean canTransitionTo(AgentTaskStatus target) {
        return allowedTargets().contains(target);
    }

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED;
    }

    private Set<AgentTaskStatus> allowedTargets() {
        return switch (this) {
            case WAITING -> EnumSet.of(RUNNING, CANCELLED);
            case RUNNING ->
                    EnumSet.of(AWAITING_CONFIRMATION, RETRY_WAIT, SUCCEEDED, FAILED, CANCELLED);
            case AWAITING_CONFIRMATION -> EnumSet.of(WAITING, RUNNING, CANCELLED);
            case RETRY_WAIT -> EnumSet.of(WAITING, RUNNING, FAILED, CANCELLED);
            case FAILED -> EnumSet.of(WAITING, RUNNING, CANCELLED);
            case SUCCEEDED -> EnumSet.noneOf(AgentTaskStatus.class);
            case CANCELLED -> EnumSet.of(WAITING);
        };
    }
}
