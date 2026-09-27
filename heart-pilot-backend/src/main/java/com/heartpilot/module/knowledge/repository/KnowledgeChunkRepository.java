package com.heartpilot.module.knowledge.repository;

import com.heartpilot.module.knowledge.entity.KnowledgeChunk;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * KnowledgeChunk 切片的 Spring Data JPA Repository。
 * 支撑按文档查询切片列表、级联删除切片。
 */
public interface KnowledgeChunkRepository extends JpaRepository<KnowledgeChunk, Long> {
    /** 查询某文档的全部切片，按 chunkIndex 升序，用于删除文档时收集 vectorId */
    List<KnowledgeChunk> findByDocumentIdOrderByChunkIndexAsc(Long documentId);

    /** 按文档 ID 批量删除全部切片，删除文档时级联调用 */
    void deleteByDocumentId(Long documentId);
}
