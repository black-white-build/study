package com.heartpilot.module.knowledge.controller;

import com.heartpilot.common.api.PageResponse;
import com.heartpilot.module.file.dto.ResourceDtos;
import com.heartpilot.module.knowledge.service.KnowledgeService;
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

/**
 * 知识库（RAG）管理端 REST 接口，路径前缀 /admin/knowledge。
 * 仅 ADMIN 角色可访问（类级 @PreAuthorize），提供文档分页列表、上传（自动解析切片向量化）、删除接口。
 */
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

    /**
     * 分页列出全部知识文档，默认按创建时间倒序，每页 20 条。
     */
    @GetMapping
    PageResponse<ResourceDtos.KnowledgeDocumentResponse> list(
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
                    Pageable pageable) {
        return PageResponse.from(
                service.list(pageable), ResourceDtos.KnowledgeDocumentResponse::from);
    }

    /**
     * 上传知识文档（multipart/form-data），后台同步完成解析、切片、向量化。
     * @param file 上传文件（Markdown/TXT/PDF/Word）
     * @param category 文档分类，默认"关系成长"
     */
    @PostMapping("/documents")
    ResourceDtos.KnowledgeDocumentResponse upload(
            @RequestPart("file") MultipartFile file,
            @RequestParam(defaultValue = "关系成长") String category) {
        return ResourceDtos.KnowledgeDocumentResponse.from(
                service.upload(file, category, current.id()));
    }

    /**
     * 删除指定文档，级联清理切片、PGVector 向量与对象存储文件。
     * @param id 文档 ID
     */
    @DeleteMapping("/documents/{id}")
    void delete(@PathVariable Long id) {
        service.delete(id);
    }
}
