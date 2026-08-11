package com.heartpilot.module.knowledge.controller;

import com.heartpilot.common.api.PageResponse;
import com.heartpilot.module.file.dto.ResourceDtos;
import com.heartpilot.module.knowledge.service.impl.KnowledgeService;
import com.heartpilot.security.CurrentUser;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/admin/knowledge")
@PreAuthorize("hasRole('ADMIN')")
public class KnowledgeController {
    private final KnowledgeService service;
    private final CurrentUser current;

    public KnowledgeController(KnowledgeService service, CurrentUser current) {
        this.service = service;
        this.current = current;
    }

    @GetMapping
    PageResponse<ResourceDtos.KnowledgeDocumentResponse> list(
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
                    Pageable pageable) {
        return PageResponse.from(
                service.list(pageable), ResourceDtos.KnowledgeDocumentResponse::from);
    }

    @PostMapping("/documents")
    ResourceDtos.KnowledgeDocumentResponse upload(
            @RequestPart("file") MultipartFile file,
            @RequestParam(defaultValue = "关系成长") String category) {
        return ResourceDtos.KnowledgeDocumentResponse.from(
                service.upload(file, category, current.id()));
    }

    @DeleteMapping("/documents/{id}")
    void delete(@PathVariable Long id) {
        service.delete(id);
    }
}
