package com.heartpilot.module.user.dto;

import com.heartpilot.module.user.entity.AppUser;
import com.heartpilot.module.user.entity.RelationshipProfile;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public final class UserDtos {
    private UserDtos() {}

    public record UserResponse(
            Long id,
            String username,
            String nickname,
            String role,
            String emotionStatus,
            String avatarUrl) {
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

    public record UpdateUserRequest(
            @Size(max = 64) String nickname,
            @Size(max = 32) String emotionStatus,
            @Size(max = 500) String avatarUrl) {}

    public record ProfileRequest(
            @Size(max = 32) String relationshipStatus,
            @Min(0) @Max(1_200) Integer relationshipMonths,
            @Size(max = 200) String communicationStyle,
            @Size(max = 1_000) String concerns,
            @Size(max = 1_000) String preferences,
            @Size(max = 1_000) String boundaries) {}

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
