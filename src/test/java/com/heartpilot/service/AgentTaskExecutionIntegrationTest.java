package com.heartpilot.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.heartpilot.domain.AgentTask;
import com.heartpilot.domain.AppUser;
import com.heartpilot.domain.enums.AgentTaskStatus;
import com.heartpilot.domain.enums.AgentTaskStepStatus;
import com.heartpilot.repository.AppUserRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class AgentTaskExecutionIntegrationTest {
    @Autowired AgentTaskService tasks;
    @Autowired AppUserRepository users;
    @Autowired ObjectMapper json;

    @Test
    void taskPersistsEachStepAndWaitsForHumanConfirmation() throws Exception {
        AppUser user = new AppUser();
        user.setUsername("runner-" + System.nanoTime());
        user.setPasswordHash("not-used-in-this-test");
        user.setNickname("测试用户");
        user = users.save(user);
        long savedUserId = user.getId();
        AgentTask task =
                tasks.create(
                        savedUserId,
                        "南宁周末计划",
                        "在南宁安排一次安静散步",
                        Map.of(
                                "province",
                                "广西壮族自治区",
                                "city",
                                "南宁市",
                                "budget",
                                300,
                                "questions",
                                List.of("安静散步")),
                        "execution-" + System.nanoTime());

        tasks.run(task.getId(), user.getId());
        Instant deadline = Instant.now().plus(Duration.ofSeconds(8));
        AgentTaskService.TaskDetail detail;
        do {
            Thread.sleep(50);
            detail = tasks.get(task.getId(), user.getId());
        } while (detail.task().getStatus() != AgentTaskStatus.AWAITING_CONFIRMATION
                && !detail.task().getStatus().isTerminal()
                && Instant.now().isBefore(deadline));

        assertEquals(AgentTaskStatus.AWAITING_CONFIRMATION, detail.task().getStatus());
        assertEquals(5, detail.task().getCurrentStep());
        assertTrue(
                detail.steps().subList(0, 4).stream()
                        .allMatch(step -> step.getStatus() == AgentTaskStepStatus.COMPLETED));
        assertEquals(AgentTaskStepStatus.WAITING_CONFIRMATION, detail.steps().get(4).getStatus());
        assertEquals(1, detail.toolCalls().size());
        assertNotNull(detail.task().getJourneyEvidenceJson());
        assertNotNull(detail.task().getEvidenceUpdatedAt());
        assertTrue(detail.executionEvents().size() >= 6);
        assertTrue(
                detail.executionEvents().stream()
                        .anyMatch(event -> event.getTitle().contains("检索")));
        assertTrue(
                detail.executionEvents().stream()
                        .anyMatch(event -> event.getPhase().name().equals("ROUTE")));

        org.junit.jupiter.api.Assertions.assertThrows(
                com.heartpilot.web.ApiException.class,
                () ->
                        tasks.confirm(
                                task.getId(),
                                savedUserId,
                                false,
                                "",
                                "广西壮族自治区",
                                "南宁市",
                                new BigDecimal("-1"),
                                List.of("只回答最后一次修改后的问题")));

        tasks.confirm(
                task.getId(),
                user.getId(),
                false,
                "",
                "广西壮族自治区",
                "南宁市",
                new BigDecimal("7000.00"),
                List.of("只回答最后一次修改后的问题"));
        deadline = Instant.now().plus(Duration.ofSeconds(8));
        do {
            Thread.sleep(50);
            detail = tasks.get(task.getId(), user.getId());
        } while ((detail.task().getStatus() != AgentTaskStatus.AWAITING_CONFIRMATION
                        || detail.task().getVersionNo() != 1)
                && !detail.task().getStatus().isTerminal()
                && Instant.now().isBefore(deadline));

        Map<String, Object> revisedParameters =
                json.readValue(detail.task().getParametersJson(), new TypeReference<>() {});
        assertEquals("7000", String.valueOf(revisedParameters.get("budget")));
        assertEquals(List.of("只回答最后一次修改后的问题"), revisedParameters.get("questions"));
        assertTrue(detail.task().getPlanPreview().contains("预算上限：7000 元"));
        assertTrue(
                detail.executionEvents().stream().anyMatch(event -> event.getTaskVersion() == 0));
        assertTrue(
                detail.executionEvents().stream().anyMatch(event -> event.getTaskVersion() == 1));
        assertTrue(
                detail.executionEvents().stream()
                        .filter(event -> event.getTaskVersion() == 1)
                        .anyMatch(event -> event.getDetail().contains("预算：7000")));
    }
}
