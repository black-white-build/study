package com.heartpilot.module.conversation.dto;

import com.heartpilot.module.conversation.entity.AiConversation;
import com.heartpilot.module.conversation.entity.AiMessage;
import com.heartpilot.module.conversation.entity.enums.AiMessageStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public final class ConversationDtos {
    private ConversationDtos() {}

    public record CreateRequest(@Size(max = 120) String title) {}

    public record RenameRequest(@NotBlank @Size(max = 120) String title) {}

    public record SendRequest(@NotBlank @Size(max = 12_000) String content) {}

    public record ConversationResponse(
            Long id,
            String title,
            String model,
            int contextLimit,
            Instant lastMessageAt,
            Instant createdAt,
            Instant updatedAt) {
        public static ConversationResponse from(AiConversation entity) {
            return new ConversationResponse(
                    entity.getId(),
                    entity.getTitle(),
                    entity.getModel(),
                    entity.getContextLimit(),
                    entity.getLastMessageAt(),
                    entity.getCreatedAt(),
                    entity.getUpdatedAt());
        }
    }

    public record MessageResponse(
            Long id,
            String role,
            String content,
            int inputTokens,
            int outputTokens,
            AiMessageStatus status,
            String model,
            String errorMessage,
            String sourcesJson,
            Long regeneratedFromId,
            boolean cacheHit,
            Long providerLatencyMs,
            long inputCostMicros,
            long outputCostMicros,
            long estimatedCostMicros,
            long cacheSavedCostMicros,
            Instant createdAt) {
        public static MessageResponse from(AiMessage entity) {
            return new MessageResponse(
                    entity.getId(),
                    entity.getRole(),
                    entity.getContent(),
                    entity.getInputTokens(),
                    entity.getOutputTokens(),
                    entity.getStatus(),
                    entity.getModel(),
                    entity.getErrorMessage(),
                    entity.getSourcesJson(),
                    entity.getRegeneratedFromId(),
                    entity.isCacheHit(),
                    entity.getProviderLatencyMs(),
                    entity.getInputCostMicros(),
                    entity.getOutputCostMicros(),
                    entity.getEstimatedCostMicros(),
                    entity.getCacheSavedCostMicros(),
                    entity.getCreatedAt());
        }
    }
}
