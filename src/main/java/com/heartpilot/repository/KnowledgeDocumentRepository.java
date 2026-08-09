package com.heartpilot.repository;

import com.heartpilot.domain.KnowledgeDocument;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KnowledgeDocumentRepository extends JpaRepository<KnowledgeDocument, Long> {
    Page<KnowledgeDocument> findAll(Pageable pageable);
}
