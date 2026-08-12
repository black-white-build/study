package com.heartpilot.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.heartpilot.module.report.entity.EmotionReport;
import com.heartpilot.module.report.repository.ReportRepository;
import com.heartpilot.module.user.repository.AppUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class SecurityFlowIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired AppUserRepository users;
    @Autowired ReportRepository reports;

    @Test
    void jwtProtectsAndIsolatesConversationData() throws Exception {
        String suffix = Long.toString(System.nanoTime());
        String tokenA = register("usera" + suffix);
        String tokenB = register("userb" + suffix);
        String body =
                mvc.perform(
                                post("/conversations")
                                        .header("Authorization", "Bearer " + tokenA)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"title\":\"私密会话\"}"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.title").value("私密会话"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        long id = json.readTree(body).get("id").asLong();
        mvc.perform(
                        get("/conversations/" + id + "/messages")
                                .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());
        mvc.perform(get("/conversations")).andExpect(status().isUnauthorized());
    }

    @Test
    void taskCreationIsIdempotentAndListIsPaged() throws Exception {
        String token = register("taskuser" + System.nanoTime());
        String payload =
                """
                {"title":"南宁行动计划","objective":"周末在南宁安排一次安静散步",
                 "parameters":{"province":"广西壮族自治区","city":"南宁市","budget":300,"questions":["安静散步"]}}
                """;
        String first = createTask(token, "resume-demo-key", payload);
        String second = createTask(token, "resume-demo-key", payload);
        long firstId = json.readTree(first).get("id").asLong();
        long secondId = json.readTree(second).get("id").asLong();

        org.junit.jupiter.api.Assertions.assertEquals(firstId, secondId);
        mvc.perform(get("/agent-tasks?page=0&size=10").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(firstId));
    }

    @Test
    void jwtIsolatesTasksAndReportsBetweenUsers() throws Exception {
        String suffix = Long.toString(System.nanoTime());
        String usernameA = "ownera" + suffix;
        String tokenA = register(usernameA);
        String tokenB = register("ownerb" + suffix);
        String payload =
                """
                {"title":"私密行动计划","objective":"只允许创建者访问",
                 "parameters":{"province":"广西壮族自治区","city":"南宁市","budget":300,
                 "questions":["如何安排沟通步骤"]}}
                """;
        long taskId =
                json.readTree(createTask(tokenA, "private-" + suffix, payload)).get("id").asLong();

        Long ownerId = users.findByUsernameIgnoreCase(usernameA).orElseThrow().getId();
        EmotionReport report = new EmotionReport();
        report.setUserId(ownerId);
        report.setTitle("私密报告");
        report = reports.save(report);

        mvc.perform(get("/agent-tasks/" + taskId).header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());
        mvc.perform(get("/reports/" + report.getId()).header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());
    }

    private String createTask(String token, String key, String payload) throws Exception {
        return mvc.perform(
                        post("/agent-tasks")
                                .header("Authorization", "Bearer " + token)
                                .header("Idempotency-Key", key)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(payload))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private String register(String username) throws Exception {
        String body =
                mvc.perform(
                                post("/auth/register")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"username\":\""
                                                        + username
                                                        + "\",\"password\":\"password-123\",\"nickname\":\"测试用户\"}"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.accessToken").isNotEmpty())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        JsonNode node = json.readTree(body);
        return node.get("accessToken").asText();
    }
}
