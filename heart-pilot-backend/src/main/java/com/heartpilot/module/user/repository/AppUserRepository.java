package com.heartpilot.module.user.repository;

import com.heartpilot.module.user.entity.AppUser;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** 用户 Repository，操作实体 AppUser（表 app_user）。 */
public interface AppUserRepository extends JpaRepository<AppUser, Long> {
    /** 按用户名大小写不敏感查询，用于登录 */
    Optional<AppUser> findByUsernameIgnoreCase(String username);

    /** 判断用户名是否已被注册（大小写不敏感），用于注册查重 */
    boolean existsByUsernameIgnoreCase(String username);
}
