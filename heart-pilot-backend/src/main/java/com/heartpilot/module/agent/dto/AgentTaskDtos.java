package com.heartpilot.module.agent.dto;

import com.heartpilot.module.agent.entity.AgentExecutionEvent;
import com.heartpilot.module.agent.entity.AgentTask;
import com.heartpilot.module.agent.entity.AgentTaskStep;
import com.heartpilot.module.agent.entity.PlanActionItem;
import com.heartpilot.module.agent.entity.PlanVersion;
import com.heartpilot.module.agent.entity.ToolCallRecord;
import com.heartpilot.module.agent.entity.enums.AgentExecutionEventStatus;
import com.heartpilot.module.agent.entity.enums.AgentExecutionEventType;
import com.heartpilot.module.agent.entity.enums.AgentExecutionPhase;
import com.heartpilot.module.agent.entity.enums.AgentTaskStatus;
import com.heartpilot.module.agent.entity.enums.AgentTaskStepStatus;
import com.heartpilot.module.agent.entity.enums.ToolCallStatus;
import com.heartpilot.module.agent.service.AgentTaskService;
import com.heartpilot.module.file.entity.GeneratedFile;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Agent 任务相关的请求/响应 DTO 集合。
 * 统一用 record 定义不可变数据结构：CreateRequest/ConfirmRequest 为入参，
 * 其余 *Response 为出参，并通过静态 from 方法从 Entity 转换而来，
 * 隔离内部实体与对外接口字段。私有构造器防止该工具类被实例化。
 */
public final class AgentTaskDtos {
    private AgentTaskDtos() {}

    /**
     * 创建任务请求。
     * @param title 任务标题（最长 140）
     * @param objective 用户目标描述（必填，最长 8000）
     * @param parameters 城市、预算等结构化参数，以 Map 透传
     */
    public record CreateRequest(
            @Size(max = 140) String title,
            @NotBlank @Size(max = 8_000) String objective,
            Map<String, Object> parameters) {}

    /**
     * 用户确认/驳回候选计划请求。
     * @param approved true=确认通过，false=驳回重规划
     * @param note 用户备注/修改意见（最长 2000）
     * @param province 调整后的省份
     * @param city 调整后的城市
     * @param budget 调整后的预算
     * @param questions 用户补充的问题列表（单条最长 500）
     */
    public record ConfirmRequest(
            boolean approved,
            @Size(max = 2_000) String note,
            @Size(max = 30) String province,
            @Size(max = 80) String city,
            BigDecimal budget,
            List<@Size(max = 500) String> questions) {}

    /**
     * 任务列表/详情中的任务基本信息响应。
     * 字段与 AgentTask 实体对应，可靠性字段（重试、心跳、版本号等）一并透出供前端展示。
     */
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
        /** 从实体转换，对可空的重试/版本字段做兜底默认值 */
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

    /**
     * 任务执行步骤响应。
     * @param stepNo 步骤编号（从 1 开始）
     * @param confirmationRequired 该步骤是否需要用户确认后才继续
     */
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

    /**
     * 工具调用记录响应，透出给前端展示 Agent 调用了哪些工具及其结果摘要。
     */
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

    /**
     * 执行事件时间线响应（思考/行动/观察等），供前端回放 Agent 执行过程。
     */
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

    /**
     * 生成文件（如 PDF 报告）信息响应，可能为 null（尚未生成）。
     */
    public record FileResponse(
            Long id,
            String fileName,
            String contentType,
            long sizeBytes,
            String businessType,
            Long businessId,
            Instant createdAt) {
        /** 实体为空时返回 null，表示尚无文件 */
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

    /**
     * 任务详情聚合响应：任务基本信息 + 步骤 + 工具调用 + 执行事件 + PDF 文件。
     */
    public record TaskDetailResponse(
            TaskResponse task,
            List<StepResponse> steps,
            List<ToolCallResponse> toolCalls,
            List<ExecutionEventResponse> executionEvents,
            FileResponse pdfFile) {}

    /**
     * 计划版本响应：历史版本列表中的一项。
     */
    public record PlanVersionResponse(
            Long id,
            int versionNo,
            String status,
            String previewText,
            String note,
            Instant createdAt) {
        public static PlanVersionResponse from(PlanVersion entity) {
            return new PlanVersionResponse(
                    entity.getId(),
                    entity.getVersionNo(),
                    entity.getStatus().name(),
                    entity.getPreviewText(),
                    entity.getNote(),
                    entity.getCreatedAt());
        }
    }

    /**
     * 计划行动条目响应：前端按执行方式渲染类型卡片。
     * executionKind / goalType / riskLevel / status 均以枚举名（大写）透出，
     * payload 已反序列化为 Map，sourceReferences 为字符串数组。
     */
    public record PlanItemResponse(
            Long id,
            int sequenceNo,
            String title,
            String executionKind,
            String goalType,
            String instruction,
            String timingSuggestion,
            Integer estimatedDurationMinutes,
            BigDecimal estimatedCost,
            String riskLevel,
            boolean requiresConfirmation,
            String status,
            Map<String, Object> payload,
            List<String> sourceReferences) {
        public static PlanItemResponse from(PlanActionItem entity) {
            return new PlanItemResponse(
                    entity.getId(),
                    entity.getSequenceNo(),
                    entity.getTitle(),
                    entity.getExecutionKind().name(),
                    entity.getGoalType() == null ? null : entity.getGoalType().name(),
                    entity.getInstruction(),
                    entity.getTimingSuggestion(),
                    entity.getEstimatedDurationMinutes(),
                    entity.getEstimatedCost(),
                    entity.getRiskLevel().name(),
                    entity.isRequiresConfirmation(),
                    entity.getStatus().name(),
                    parsePayload(entity.getPayloadJson()),
                    parseRefs(entity.getSourceReferencesJson()));
        }
    }

    /**
     * 计划详情响应：计划信息 + 历史版本列表（最新在前） + 最新版本的行动条目。
     * 计划尚未生成时 planId 为 null。
     */
    public record PlanDetailResponse(
            Long planId,
            String goalType,
            String planStatus,
            List<PlanVersionResponse> versions,
            List<PlanItemResponse> currentItems) {
        public static PlanDetailResponse from(AgentTaskService.PlanDetail detail) {
            return new PlanDetailResponse(
                    detail.plan() == null ? null : detail.plan().getId(),
                    detail.plan() == null || detail.plan().getGoalType() == null
                            ? null
                            : detail.plan().getGoalType().name(),
                    detail.plan() == null ? null : detail.plan().getStatus().name(),
                    detail.versions().stream().map(PlanVersionResponse::from).toList(),
                    detail.currentItems().stream().map(PlanItemResponse::from).toList());
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    /** payload JSON 解析为 Map，解析失败返回空 Map（前端按空渲染，不阻断） */
    private static Map<String, Object> parsePayload(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) return Map.of();
        try {
            return JSON.readValue(payloadJson, new TypeReference<>() {});
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    /** 引用来源 JSON 数组解析，解析失败返回空列表 */
    private static List<String> parseRefs(String refsJson) {
        if (refsJson == null || refsJson.isBlank()) return List.of();
        try {
            return JSON.readValue(refsJson, new TypeReference<>() {});
        } catch (Exception ignored) {
            return List.of();
        }
    }
}
