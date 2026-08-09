package com.heartpilot;

import com.heartpilot.config.DotEnvLoader;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@EnableScheduling
public class HeartPilotApplication {

    public static void main(String[] args) {
        DotEnvLoader.load();
        SpringApplication.run(HeartPilotApplication.class, args);
    }
}
