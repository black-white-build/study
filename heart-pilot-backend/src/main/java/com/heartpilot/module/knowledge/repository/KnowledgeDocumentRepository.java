package com.heartpilot.module.knowledge.repository;

import com.heartpilot.module.knowledge.entity.KnowledgeDocument;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * KnowledgeDocument 文档元数据的 Spring Data JPA Repository。
 * 支撑管理员后台分页列出全部文档。
 */
public interface KnowledgeDocumentRepository extends JpaRepository<KnowledgeDocument, Long> {
    /** 分页查询全部文档（管理员视角，不按用户过滤），排序由 Controller 的 Pageable 指定 */
    Page<KnowledgeDocument> findAll(Pageable pageable);
}
