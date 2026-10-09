package com.heartpilot;

import com.heartpilot.config.DotEnvLoader;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * HeartPilot 心旅后端启动类。 排除 Spring Security 默认的 UserDetailsServiceAutoConfiguration（项目自定义用户体系），
 * 开启 @EnableScheduling 以支持定时任务（如 Agent 任务恢复扫描）。
 */
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@EnableScheduling
public class HeartPilotApplication {

    public static void main(String[] args) {
        DotEnvLoader.load();
        SpringApplication.run(HeartPilotApplication.class, args);
    }
}
