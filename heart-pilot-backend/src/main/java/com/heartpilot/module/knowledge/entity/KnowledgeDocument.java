package com.heartpilot.module.knowledge.entity;

import com.heartpilot.common.entity.BaseEntity;
import com.heartpilot.module.knowledge.entity.enums.KnowledgeDocumentStatus;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(
        name = "knowledge_document",
        indexes = @Index(name = "idx_doc_status", columnList = "status,createdAt"))
@Getter
@Setter
@NoArgsConstructor
public class KnowledgeDocument extends BaseEntity {
    @Column(nullable = false)
    private Long uploadedBy;

    @Column(nullable = false, length = 255)
    private String originalName;

    @Column(nullable = false, length = 100)
    private String contentType;

    @Column(nullable = false)
    private long sizeBytes;

    @Column(nullable = false, length = 500)
    private String storageKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private KnowledgeDocumentStatus status = KnowledgeDocumentStatus.UPLOADED;

    @Column(length = 64)
    private String category;

    @Column(length = 500)
    private String errorMessage;

    @Column(nullable = false)
    private int chunkCount;
}
