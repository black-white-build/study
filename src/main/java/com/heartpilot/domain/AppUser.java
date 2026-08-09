package com.heartpilot.domain;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(
        name = "app_user",
        indexes = @Index(name = "idx_user_username", columnList = "username", unique = true))
@Getter
@Setter
@NoArgsConstructor
public class AppUser extends BaseEntity {
    @Column(nullable = false, unique = true, length = 64)
    private String username;

    @Column(nullable = false, length = 100)
    private String passwordHash;

    @Column(nullable = false, length = 64)
    private String nickname;

    @Column(nullable = false, length = 16)
    private String role = "USER";

    @Column(length = 32)
    private String emotionStatus = "平静";

    @Column(length = 500)
    private String avatarUrl;

    @Column(nullable = false)
    private boolean enabled = true;
}
