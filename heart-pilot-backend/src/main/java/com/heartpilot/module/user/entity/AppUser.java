package com.heartpilot.module.user.entity;

import com.heartpilot.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

/** 应用用户实体，对应数据库表 app_user。 存储登录账号、密码哈希、昵称、角色、情绪状态与头像等基础信息。 继承 BaseEntity 获得 id、创建时间、更新时间等公共字段。 */
@Entity
@Table(
        name = "app_user",
        indexes = @Index(name = "idx_user_username", columnList = "username", unique = true))
@Getter
@Setter
@NoArgsConstructor
public class AppUser extends BaseEntity {
    /** 登录用户名，全局唯一，注册时大小写不敏感 */
    @Column(nullable = false, unique = true, length = 64)
    private String username;

    /** BCrypt 加密后的密码哈希，绝不存明文 */
    @Column(nullable = false, length = 100)
    private String passwordHash;

    /** 展示昵称 */
    @Column(nullable = false, length = 64)
    private String nickname;

    /** 用户角色，默认普通用户 USER，预留 ADMIN 等扩展 */
    @Column(nullable = false, length = 16)
    private String role = "USER";

    /** 当前情绪状态，默认"平静"，由前端/咨询流程更新 */
    @Column(length = 32)
    private String emotionStatus = "平静";

    /** 头像 URL */
    @Column(length = 500)
    private String avatarUrl;

    /** 账号是否启用，禁用后无法登录 */
    @Column(nullable = false)
    private boolean enabled = true;
}
