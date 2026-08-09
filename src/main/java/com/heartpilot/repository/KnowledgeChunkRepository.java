package com.heartpilot.repository;

import com.heartpilot.domain.KnowledgeChunk;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KnowledgeChunkRepository extends JpaRepository<KnowledgeChunk, Long> {
    List<KnowledgeChunk> findByDocumentIdOrderByChunkIndexAsc(Long documentId);

    void deleteByDocumentId(Long documentId);
}
