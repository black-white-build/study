package com.heartpilot.module.file.dto;

import com.heartpilot.module.file.entity.GeneratedFile;
import com.heartpilot.module.knowledge.entity.KnowledgeDocument;
import com.heartpilot.module.knowledge.entity.enums.KnowledgeDocumentStatus;
import com.heartpilot.module.report.entity.EmotionReport;
import java.time.Instant;

public final class ResourceDtos {
    private ResourceDtos() {}

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

    public record ReportResponse(
            Long id,
            Long conversationId,
            String title,
            String reportType,
            String problemSummary,
            String relationshipStatus,
            String conflictType,
            String riskLevel,
            String analysis,
            String actionsJson,
            Instant reviewAt,
            Instant createdAt,
            Instant updatedAt) {
        public static ReportResponse from(EmotionReport entity) {
            return new ReportResponse(
                    entity.getId(),
                    entity.getConversationId(),
                    entity.getTitle(),
                    entity.getReportType(),
                    entity.getProblemSummary(),
                    entity.getRelationshipStatus(),
                    entity.getConflictType(),
                    entity.getRiskLevel(),
                    entity.getAnalysis(),
                    entity.getActionsJson(),
                    entity.getReviewAt(),
                    entity.getCreatedAt(),
                    entity.getUpdatedAt());
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
