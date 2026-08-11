package com.heartpilot.module.knowledge.repository;

import com.heartpilot.module.knowledge.entity.KnowledgeChunk;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KnowledgeChunkRepository extends JpaRepository<KnowledgeChunk, Long> {
    List<KnowledgeChunk> findByDocumentIdOrderByChunkIndexAsc(Long documentId);

    void deleteByDocumentId(Long documentId);
}
