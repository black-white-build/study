package com.heartpilot.module.knowledge.service;

import com.heartpilot.module.knowledge.entity.KnowledgeDocument;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.multipart.MultipartFile;

public interface KnowledgeService {
    KnowledgeDocument upload(MultipartFile file, String category, Long userId);

    List<Source> retrieve(String query, int limit);

    Page<KnowledgeDocument> list(Pageable pageable);

    void delete(Long id);

    public record Source(String documentName, String section, String content, int chunkIndex) {}
}
