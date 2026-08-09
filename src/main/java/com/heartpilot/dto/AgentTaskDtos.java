package com.heartpilot.dto;

import com.heartpilot.domain.AgentExecutionEvent;
import com.heartpilot.domain.AgentTask;
import com.heartpilot.domain.AgentTaskStep;
import com.heartpilot.domain.GeneratedFile;
import com.heartpilot.domain.ToolCallRecord;
import com.heartpilot.domain.enums.AgentExecutionEventStatus;
import com.heartpilot.domain.enums.AgentExecutionEventType;
import com.heartpilot.domain.enums.AgentExecutionPhase;
import com.heartpilot.domain.enums.AgentTaskStatus;
import com.heartpilot.domain.enums.AgentTaskStepStatus;
import com.heartpilot.domain.enums.ToolCallStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class AgentTaskDtos {
    private AgentTaskDtos() {}

    public record CreateRequest(
            @Size(max = 140) String title,
            @NotBlank @Size(max = 8_000) String objective,
            Map<String, Object> parameters) {}

    public record ConfirmRequest(
            boolean approved,
            @Size(max = 2_000) String note,
            @Size(max = 80) String city,
            BigDecimal budget,
            List<@Size(max = 500) String> questions) {}

    public record TaskResponse(
            Long id,
            String title,
            String objective,
            AgentTaskStatus status,
            String parametersJson,
            String planPreview,
            String finalResult,
            String journeyEvidenceJson,
            String ambienceImagesJson,
            Instant evidenceUpdatedAt,
            int currentStep,
            int maxSteps,
            int versionNo,
            int retryCount,
            int maxRetries,
            Instant heartbeatAt,
            Instant nextRetryAt,
            String errorMessage,
            long lockVersion,
            Instant createdAt,
            Instant updatedAt) {
        public static TaskResponse from(AgentTask entity) {
            return new TaskResponse(
                    entity.getId(),
                    entity.getTitle(),
                    entity.getObjective(),
                    entity.getStatus(),
                    entity.getParametersJson(),
                    entity.getPlanPreview(),
                    entity.getFinalResult(),
                    entity.getJourneyEvidenceJson(),
                    entity.getAmbienceImagesJson(),
                    entity.getEvidenceUpdatedAt(),
                    entity.getCurrentStep(),
                    entity.getMaxSteps(),
                    entity.getVersionNo(),
                    entity.getRetryCount() == null ? 0 : entity.getRetryCount(),
                    entity.getMaxRetries() == null ? 2 : entity.getMaxRetries(),
                    entity.getHeartbeatAt(),
                    entity.getNextRetryAt(),
                    entity.getErrorMessage(),
                    entity.getLockVersion() == null ? 0 : entity.getLockVersion(),
                    entity.getCreatedAt(),
                    entity.getUpdatedAt());
        }
    }

    public record StepResponse(
            Long id,
            int stepNo,
            String name,
            AgentTaskStepStatus status,
            String detail,
            Instant startedAt,
            Instant completedAt,
            int retryCount,
            boolean confirmationRequired) {
        public static StepResponse from(AgentTaskStep entity) {
            return new StepResponse(
                    entity.getId(),
                    entity.getStepNo(),
                    entity.getName(),
                    entity.getStatus(),
                    entity.getDetail(),
                    entity.getStartedAt(),
                    entity.getCompletedAt(),
                    entity.getRetryCount(),
                    entity.isConfirmationRequired());
        }
    }

    public record ToolCallResponse(
            Long id,
            Long stepId,
            String toolName,
            String argumentsJson,
            String resultSummary,
            ToolCallStatus status,
            Long durationMs,
            String errorMessage,
            String idempotencyKey,
            Instant createdAt) {
        public static ToolCallResponse from(ToolCallRecord entity) {
            return new ToolCallResponse(
                    entity.getId(),
                    entity.getStepId(),
                    entity.getToolName(),
                    entity.getArgumentsJson(),
                    entity.getResultSummary(),
                    entity.getStatus(),
                    entity.getDurationMs(),
                    entity.getErrorMessage(),
                    entity.getIdempotencyKey(),
                    entity.getCreatedAt());
        }
    }

    public record ExecutionEventResponse(
            Long id,
            int taskVersion,
            Integer stepNo,
            AgentExecutionPhase phase,
            AgentExecutionEventType eventType,
            AgentExecutionEventStatus status,
            String title,
            String detail,
            String provider,
            String toolName,
            Integer itemCount,
            Long durationMs,
            String sourceUrl,
            String metadataJson,
            Instant createdAt) {
        public static ExecutionEventResponse from(AgentExecutionEvent entity) {
            return new ExecutionEventResponse(
                    entity.getId(),
                    entity.getTaskVersion(),
                    entity.getStepNo(),
                    entity.getPhase(),
                    entity.getEventType(),
                    entity.getStatus(),
                    entity.getTitle(),
                    entity.getDetail(),
                    entity.getProvider(),
                    entity.getToolName(),
                    entity.getItemCount(),
                    entity.getDurationMs(),
                    entity.getSourceUrl(),
                    entity.getMetadataJson(),
                    entity.getCreatedAt());
        }
    }

    public record FileResponse(
            Long id,
            String fileName,
            String contentType,
            long sizeBytes,
            String businessType,
            Long businessId,
            Instant createdAt) {
        public static FileResponse from(GeneratedFile entity) {
            if (entity == null) return null;
            return new FileResponse(
                    entity.getId(),
                    entity.getFileName(),
                    entity.getContentType(),
                    entity.getSizeBytes(),
                    entity.getBusinessType(),
                    entity.getBusinessId(),
                    entity.getCreatedAt());
        }
    }

    public record TaskDetailResponse(
            TaskResponse task,
            List<StepResponse> steps,
            List<ToolCallResponse> toolCalls,
            List<ExecutionEventResponse> executionEvents,
            FileResponse pdfFile) {}
}
