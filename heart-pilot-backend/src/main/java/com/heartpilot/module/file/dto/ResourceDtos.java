package com.heartpilot.module.file.dto;

import com.heartpilot.module.file.entity.GeneratedFile;
import com.heartpilot.module.knowledge.entity.KnowledgeDocument;
import com.heartpilot.module.knowledge.entity.enums.KnowledgeDocumentStatus;
import com.heartpilot.module.knowledge.entity.enums.KnowledgeEvidenceLevel;
import com.heartpilot.module.knowledge.entity.enums.KnowledgeReviewStatus;
import com.heartpilot.module.knowledge.service.KnowledgeService;
import java.time.Instant;

/** 资源相关 DTO 聚合类。 聚合知识库文档与已生成文件两类资源的响应视图。 */
public final class ResourceDtos {
    private ResourceDtos() {}

    /**
     * 知识库文档响应体。
     *
     * @param id 文档 ID
     * @param originalName 上传时的原始文件名
     * @param contentType 文件 MIME 类型
     * @param sizeBytes 文件大小（字节）
     * @param status 文档解析/切片状态
     * @param category 文档分类
     * @param applicableScenario 适用场景标签
     * @param relationshipStage 适用关系阶段
     * @param sourceName 资料来源名称
     * @param sourceUrl 资料来源链接
     * @param contentVersion 内容版本号
     * @param reviewStatus 审核状态
     * @param riskTags 风险标签
     * @param evidenceLevel 证据等级
     * @param errorMessage 解析失败时的错误信息
     * @param chunkCount 切片数量
     * @param createdAt 创建时间
     * @param updatedAt 更新时间
     */
    public record KnowledgeDocumentResponse(
            Long id,
            String originalName,
            String contentType,
            long sizeBytes,
            KnowledgeDocumentStatus status,
            String category,
            String applicableScenario,
            String relationshipStage,
            String sourceName,
            String sourceUrl,
            String contentVersion,
            KnowledgeReviewStatus reviewStatus,
            String riskTags,
            KnowledgeEvidenceLevel evidenceLevel,
            String errorMessage,
            int chunkCount,
            Instant createdAt,
            Instant updatedAt) {
        /** 由 KnowledgeDocument 实体转换为响应 DTO */
        public static KnowledgeDocumentResponse from(KnowledgeDocument entity) {
            return new KnowledgeDocumentResponse(
                    entity.getId(),
                    entity.getOriginalName(),
                    entity.getContentType(),
                    entity.getSizeBytes(),
                    entity.getStatus(),
                    entity.getCategory(),
                    entity.getApplicableScenario(),
                    entity.getRelationshipStage(),
                    entity.getSourceName(),
                    entity.getSourceUrl(),
                    entity.getContentVersion(),
                    entity.getReviewStatus(),
                    entity.getRiskTags(),
                    entity.getEvidenceLevel(),
                    entity.getErrorMessage(),
                    entity.getChunkCount(),
                    entity.getCreatedAt(),
                    entity.getUpdatedAt());
        }
    }

    /** 管理端文档内容响应体。content 是 knowledge_chunk 中现有切片按序拼接的纯文本， 不包含 HTML，也不会触发原文件重新解析。 */
    public record KnowledgeDocumentContentResponse(
            Long documentId, String originalName, int chunkCount, String content) {
        /** 由知识服务的切片拼接结果转换为接口响应。 */
        public static KnowledgeDocumentContentResponse from(
                KnowledgeService.DocumentContent document) {
            return new KnowledgeDocumentContentResponse(
                    document.documentId(),
                    document.originalName(),
                    document.chunkCount(),
                    document.content());
        }
    }

    /**
     * 已生成文件响应体。
     *
     * @param id 文件记录 ID
     * @param fileName 下载时展示的文件名
     * @param contentType 文件 MIME 类型
     * @param sizeBytes 文件大小（字节）
     * @param businessType 业务类型（如 AGENT_TASK），用于关联业务
     * @param businessId 关联的业务记录 ID
     * @param createdAt 创建时间
     */
    public record FileResponse(
            Long id,
            String fileName,
            String contentType,
            long sizeBytes,
            String businessType,
            Long businessId,
            Instant createdAt) {
        /** 由 GeneratedFile 实体转换为响应 DTO */
        public static FileResponse from(GeneratedFile entity) {
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
}
