package com.heartpilot.module.user.dto;

import com.heartpilot.module.user.entity.AppUser;
import com.heartpilot.module.user.entity.RelationshipProfile;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/** 用户模块相关 DTO 集合，包含用户信息响应、更新请求与关系档案的请求/响应。 */
public final class UserDtos {
    private UserDtos() {}

    /**
     * 当前用户基础信息响应。
     *
     * @param id 用户 ID
     * @param username 登录用户名
     * @param nickname 展示昵称
     * @param role 角色（USER/ADMIN 等）
     * @param emotionStatus 当前情绪状态
     * @param avatarUrl 头像 URL
     */
    public record UserResponse(
            Long id,
            String username,
            String nickname,
            String role,
            String emotionStatus,
            String avatarUrl) {
        /** 从 AppUser 实体转换 */
        public static UserResponse from(AppUser entity) {
            return new UserResponse(
                    entity.getId(),
                    entity.getUsername(),
                    entity.getNickname(),
                    entity.getRole(),
                    entity.getEmotionStatus(),
                    entity.getAvatarUrl());
        }
    }

    /**
     * 局部更新用户信息请求，字段均可选，传 null 表示不修改。
     *
     * @param nickname 昵称
     * @param emotionStatus 情绪状态
     * @param avatarUrl 头像 URL
     */
    public record UpdateUserRequest(
            @Size(max = 64) String nickname,
            @Size(max = 32) String emotionStatus,
            @Size(max = 500) String avatarUrl) {}

    /**
     * 保存关系档案请求，与用户一对一。
     *
     * @param relationshipStatus 关系状态（如恋爱中/已婚等）
     * @param relationshipMonths 关系持续月数，范围 0~1200
     * @param communicationStyle 沟通方式
     * @param concerns 长期关注/担忧
     * @param preferences 偏好
     * @param boundaries 边界
     */
    public record ProfileRequest(
            @Size(max = 32) String relationshipStatus,
            @Min(0) @Max(1_200) Integer relationshipMonths,
            @Size(max = 200) String communicationStyle,
            @Size(max = 1_000) String concerns,
            @Size(max = 1_000) String preferences,
            @Size(max = 1_000) String boundaries) {}

    /**
     * 关系档案响应。
     *
     * @param id 档案 ID
     * @param relationshipStatus 关系状态
     * @param relationshipMonths 关系持续月数
     * @param communicationStyle 沟通方式
     * @param concerns 长期关注
     * @param preferences 偏好
     * @param boundaries 边界
     * @param createdAt 创建时间
     * @param updatedAt 更新时间
     */
    public record ProfileResponse(
            Long id,
            String relationshipStatus,
            Integer relationshipMonths,
            String communicationStyle,
            String concerns,
            String preferences,
            String boundaries,
            Instant createdAt,
            Instant updatedAt) {
        /** 从 RelationshipProfile 实体转换，null 实体返回 null */
        public static ProfileResponse from(RelationshipProfile entity) {
            if (entity == null) return null;
            return new ProfileResponse(
                    entity.getId(),
                    entity.getRelationshipStatus(),
                    entity.getRelationshipMonths(),
                    entity.getCommunicationStyle(),
                    entity.getConcerns(),
                    entity.getPreferences(),
                    entity.getBoundaries(),
                    entity.getCreatedAt(),
                    entity.getUpdatedAt());
        }
    }
}
