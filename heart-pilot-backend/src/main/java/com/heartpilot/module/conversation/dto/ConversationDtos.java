package com.heartpilot.module.conversation.dto;

import com.heartpilot.module.conversation.entity.AiConversation;
import com.heartpilot.module.conversation.entity.AiMessage;
import com.heartpilot.module.conversation.entity.enums.AiMessageStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/**
 * 对话模块相关 DTO 聚合类。
 * 私有构造器禁止实例化，内部 record 分别定义创建/重命名/发送消息的请求体与会话、消息的响应体。
 */
public final class ConversationDtos {
    private ConversationDtos() {}

    /**
     * 创建会话请求体。
     * @param title 会话标题，最长 120 字，可为空（空时服务端自动生成）
     */
    public record CreateRequest(@Size(max = 120) String title) {}

    /**
     * 重命名会话请求体。
     * @param title 新标题，非空且最长 120 字
     */
    public record RenameRequest(@NotBlank @Size(max = 120) String title) {}

    /**
     * 发送消息请求体。
     * @param content 用户消息内容，非空，最长 12000 字
     */
    public record SendRequest(@NotBlank @Size(max = 12_000) String content) {}

    /**
     * 会话响应体。
     * @param id 会话 ID
     * @param title 会话标题
     * @param model 该会话使用的大模型名称
     * @param contextLimit 携带到模型的最大历史消息轮数
     * @param lastMessageAt 最近一条消息时间，用于列表排序
     * @param createdAt 创建时间
     * @param updatedAt 更新时间
     */
    public record ConversationResponse(
            Long id,
            String title,
            String model,
            int contextLimit,
            Instant lastMessageAt,
            Instant createdAt,
            Instant updatedAt) {
        /** 由 AiConversation 实体转换为响应 DTO */
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

    /**
     * 消息响应体。除内容外还聚合了 token 用量、成本、缓存命中、来源引用等可观测字段。
     * @param id 消息 ID
     * @param role 消息角色（user / assistant）
     * @param content 消息文本内容
     * @param inputTokens 本次请求输入 token 数
     * @param outputTokens 本次请求输出 token 数
     * @param status 消息状态（流式中/完成/失败/取消）
     * @param model 生成该消息所用的模型
     * @param errorMessage 失败时的错误信息，正常完成时为 null
     * @param sourcesJson 检索增强来源引用的 JSON 快照
     * @param regeneratedFromId 若为重生成消息，指向被重生成的原消息 ID
     * @param cacheHit 是否命中上下文缓存
     * @param providerLatencyMs 模型提供商响应耗时（毫秒）
     * @param inputCostMicros 输入成本（微单位，1 微单位 = 1e-6 元，避免浮点误差）
     * @param outputCostMicros 输出成本（微单位）
     * @param estimatedCostMicros 估算总成本（微单位）
     * @param cacheSavedCostMicros 缓存节省的成本（微单位）
     * @param createdAt 创建时间
     */
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
        /** 由 AiMessage 实体转换为响应 DTO */
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
