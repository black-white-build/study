package com.heartpilot.config;

import com.heartpilot.domain.AppUser;
import com.heartpilot.repository.AppUserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class BootstrapAdminConfig {
    @Bean
    CommandLineRunner bootstrapAdmin(
            AppUserRepository users,
            PasswordEncoder encoder,
            @Value("${APP_ADMIN_USERNAME:}") String username,
            @Value("${APP_ADMIN_PASSWORD:}") String password) {
        return args -> {
            if (!username.isBlank()
                    && !password.isBlank()
                    && !users.existsByUsernameIgnoreCase(username)) {
                AppUser u = new AppUser();
                u.setUsername(username.toLowerCase());
                u.setPasswordHash(encoder.encode(password));
                u.setNickname("管理员");
                u.setRole("ADMIN");
                users.save(u);
            }
        };
    }
}
