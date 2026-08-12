package com.heartpilot.infrastructure.persistence;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(
        properties = {
            "spring.jpa.hibernate.ddl-auto=validate",
            "spring.flyway.enabled=true",
            "app.storage.provider=local",
            "app.storage.local-directory=./target/postgres-test-files",
            "app.rate-limit.redis-enabled=false",
            "app.agent.react-enabled=false",
            "app.agent.recovery-scan-millis=3600000"
        })
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class PostgresMigrationIntegrationTest {
    private static final DockerImageName PGVECTOR =
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres");

    @Container
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(PGVECTOR)
                    .withDatabaseName("heart_pilot")
                    .withUsername("heart_pilot")
                    .withPassword("test-only-password");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @Test
    void flywaySchemaValidatesAndSupportsCoreRegistrationAndTaskCreation() throws Exception {
        String suffix = Long.toString(System.nanoTime());
        String session =
                mvc.perform(
                                post("/auth/register")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"username\":\"pguser"
                                                        + suffix
                                                        + "\",\"password\":\"password-123\",\"nickname\":\"迁移测试\"}"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.accessToken").isNotEmpty())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        String token = json.readTree(session).get("accessToken").asText();
        mvc.perform(
                        post("/agent-tasks")
                                .header("Authorization", "Bearer " + token)
                                .header("Idempotency-Key", "pg-migration-" + suffix)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"title":"迁移验证计划","objective":"验证生产数据库核心链路",
                                         "parameters":{"province":"广西壮族自治区","city":"南宁市","budget":300,
                                         "questions":["如何安排沟通步骤"]}}
                                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.status").value("WAITING"));
    }
}
