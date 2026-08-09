package com.heartpilot.domain;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(
        name = "knowledge_chunk",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_doc_chunk",
                        columnNames = {"documentId", "chunkIndex"}))
@Getter
@Setter
@NoArgsConstructor
public class KnowledgeChunk extends BaseEntity {
    @Column(nullable = false)
    private Long documentId;

    @Column(nullable = false)
    private int chunkIndex;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(length = 500)
    private String keywords;

    @Column(length = 160)
    private String sectionTitle;

    @Column(length = 100)
    private String vectorId;

    @Column(nullable = false)
    private int tokenCount;
}
