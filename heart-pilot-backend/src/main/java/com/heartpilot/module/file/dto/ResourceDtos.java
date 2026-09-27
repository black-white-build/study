package com.heartpilot.module.file.dto;

import com.heartpilot.module.file.entity.GeneratedFile;
import com.heartpilot.module.knowledge.entity.KnowledgeDocument;
import com.heartpilot.module.knowledge.entity.enums.KnowledgeDocumentStatus;
import java.time.Instant;

/**
 * 资源相关 DTO 聚合类。
 * 聚合知识库文档与已生成文件两类资源的响应视图。
 */
public final class ResourceDtos {
    private ResourceDtos() {}

    /**
     * 知识库文档响应体。
     * @param id 文档 ID
     * @param originalName 上传时的原始文件名
     * @param contentType 文件 MIME 类型
     * @param sizeBytes 文件大小（字节）
     * @param status 文档解析/切片状态
     * @param category 文档分类
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
                    entity.getErrorMessage(),
                    entity.getChunkCount(),
                    entity.getCreatedAt(),
                    entity.getUpdatedAt());
        }
    }

    /**
     * 已生成文件响应体。
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
