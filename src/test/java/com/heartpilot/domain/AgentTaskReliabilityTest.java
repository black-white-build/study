package com.heartpilot.domain;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heartpilot.domain.enums.AgentTaskStatus;
import jakarta.persistence.Version;
import org.junit.jupiter.api.Test;

class AgentTaskReliabilityTest {
    @Test
    void stateTransitionsRejectInvalidPaths() {
        assertTrue(AgentTaskStatus.WAITING.canTransitionTo(AgentTaskStatus.RUNNING));
        assertTrue(AgentTaskStatus.RUNNING.canTransitionTo(AgentTaskStatus.RETRY_WAIT));
        assertTrue(AgentTaskStatus.RETRY_WAIT.canTransitionTo(AgentTaskStatus.WAITING));
        assertFalse(AgentTaskStatus.SUCCEEDED.canTransitionTo(AgentTaskStatus.RUNNING));
    }

    @Test
    void entityUsesJpaOptimisticLocking() throws Exception {
        assertNotNull(AgentTask.class.getDeclaredField("lockVersion").getAnnotation(Version.class));
    }
}
