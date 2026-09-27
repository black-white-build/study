package com.heartpilot;

import com.heartpilot.config.DotEnvLoader;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * HeartPilot 心旅后端启动类。
 * 排除 Spring Security 默认的 UserDetailsServiceAutoConfiguration（项目自定义用户体系），
 * 开启 @EnableScheduling 以支持定时任务（如 Agent 任务恢复扫描）。
 */
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@EnableScheduling
public class HeartPilotApplication {

    /**
     * Spring Boot 入口：先加载本地 .env 环境变量，再启动应用上下文。
     */
    public static void main(String[] args) {
        // 本地开发时把 .env 中的密钥等注入系统属性，生产环境由环境变量直接提供
        DotEnvLoader.load();
        SpringApplication.run(HeartPilotApplication.class, args);
    }
}
