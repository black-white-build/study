package com.heartpilot.config;

import com.heartpilot.module.user.entity.AppUser;
import com.heartpilot.module.user.repository.AppUserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

/** 启动时初始化管理员账号的配置类。 仅当环境变量配置了管理员账号密码、且数据库中尚不存在同名用户时，自动创建一个 ADMIN 用户， 便于首次部署后即可登录后台，避免手工写库。 */
@Configuration
public class BootstrapAdminConfig {

    /**
     * 注册一个启动后执行的命令行任务（CommandLineRunner）。 配置项来源：环境变量 APP_ADMIN_USERNAME /
     * APP_ADMIN_PASSWORD（均为空则跳过初始化）。
     *
     * @param users 用户仓库
     * @param encoder 密码编码器，对管理员密码做 BCrypt 哈希后存储
     * @param username 管理员登录名（来自 APP_ADMIN_USERNAME）
     * @param password 管理员明文密码（来自 APP_ADMIN_PASSWORD）
     */
    @Bean
    CommandLineRunner bootstrapAdmin(
            AppUserRepository users,
            PasswordEncoder encoder,
            @Value("${APP_ADMIN_USERNAME:}") String username,
            @Value("${APP_ADMIN_PASSWORD:}") String password) {
        return args -> {
            // 账号密码均非空且库中不存在同名用户时才创建，避免重复初始化覆盖已有数据
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
