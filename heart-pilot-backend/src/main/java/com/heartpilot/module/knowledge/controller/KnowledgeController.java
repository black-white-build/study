package com.heartpilot.module.knowledge.controller;

import com.heartpilot.common.api.PageResponse;
import com.heartpilot.module.file.dto.ResourceDtos;
import com.heartpilot.module.knowledge.entity.enums.KnowledgeEvidenceLevel;
import com.heartpilot.module.knowledge.entity.enums.KnowledgeReviewStatus;
import com.heartpilot.module.knowledge.service.KnowledgeService;
import com.heartpilot.security.CurrentUser;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 知识库（RAG）管理端 REST 接口，路径前缀 /admin/knowledge。 仅 ADMIN
 * 角色可访问（类级 @PreAuthorize），提供文档分页列表、上传、内容查看、审核通过和删除接口。
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

    /** 分页列出全部知识文档，默认按创建时间倒序，每页 20 条。 */
    @GetMapping
    PageResponse<ResourceDtos.KnowledgeDocumentResponse> list(
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
                    Pageable pageable) {
        return PageResponse.from(
                service.list(pageable), ResourceDtos.KnowledgeDocumentResponse::from);
    }

    /**
     * 上传知识文档（multipart/form-data），后台同步完成解析、切片、向量化。
     *
     * @param file 上传文件（Markdown/TXT/PDF/Word）
     * @param category 文档分类（必填，无默认值）
     * @param applicableScenario 适用场景标签，默认"通用沟通"
     * @param relationshipStage 适用关系阶段，默认"通用"
     * @param sourceName 资料来源名称，默认"管理员上传"
     * @param sourceUrl 资料来源链接，可为空
     * @param contentVersion 内容版本号，默认"1.0"
     * @param reviewStatus 审核状态，默认 IN_REVIEW；管理员通过审核后才可被检索
     * @param riskTags 风险标签（逗号分隔），用于路由高风险处理
     * @param evidenceLevel 证据等级，默认 UNVERIFIED
     */
    @PostMapping("/documents")
    ResourceDtos.KnowledgeDocumentResponse upload(
            @RequestPart("file") MultipartFile file,
            @RequestParam String category,
            @RequestParam(defaultValue = "通用沟通") String applicableScenario,
            @RequestParam(defaultValue = "通用") String relationshipStage,
            @RequestParam(defaultValue = "管理员上传") String sourceName,
            @RequestParam(defaultValue = "") String sourceUrl,
            @RequestParam(defaultValue = "1.0") String contentVersion,
            @RequestParam(defaultValue = "IN_REVIEW") KnowledgeReviewStatus reviewStatus,
            @RequestParam(defaultValue = "") String riskTags,
            @RequestParam(defaultValue = "UNVERIFIED") KnowledgeEvidenceLevel evidenceLevel) {
        return ResourceDtos.KnowledgeDocumentResponse.from(
                service.upload(
                        file,
                        new KnowledgeService.DocumentMetadata(
                                category,
                                applicableScenario,
                                relationshipStage,
                                sourceName,
                                sourceUrl,
                                contentVersion,
                                reviewStatus,
                                riskTags,
                                evidenceLevel),
                        current.id()));
    }

    /**
     * 查看文档已经切好的纯文本内容。内容直接读取 knowledge_chunk 并按 chunkIndex 拼接，
     * 不重新解析 PDF/Word，也不提供富文本或下载能力。
     */
    @GetMapping("/documents/{id}/content")
    ResourceDtos.KnowledgeDocumentContentResponse content(@PathVariable Long id) {
        return ResourceDtos.KnowledgeDocumentContentResponse.from(service.content(id));
    }

    /**
     * 审核通过已完成处理的文档。通过后 reviewStatus 变为 APPROVED，文档随即进入 RAG 检索池。
     */
    @PatchMapping("/documents/{id}/approve")
    ResourceDtos.KnowledgeDocumentResponse approve(@PathVariable Long id) {
        return ResourceDtos.KnowledgeDocumentResponse.from(service.approve(id));
    }

    /**
     * 删除指定文档，级联清理切片、PGVector 向量与对象存储文件。
     *
     * @param id 文档 ID
     */
    @DeleteMapping("/documents/{id}")
    void delete(@PathVariable Long id) {
        service.delete(id);
    }
}
