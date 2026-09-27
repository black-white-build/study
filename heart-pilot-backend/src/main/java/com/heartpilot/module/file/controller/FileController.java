package com.heartpilot.module.file.controller;

import com.heartpilot.common.api.PageResponse;
import com.heartpilot.common.exception.ApiException;
import com.heartpilot.module.file.dto.ResourceDtos;
import com.heartpilot.module.file.entity.GeneratedFile;
import com.heartpilot.module.file.repository.GeneratedFileRepository;
import com.heartpilot.module.file.service.StorageService;
import com.heartpilot.security.CurrentUser;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 文件资源控制器，路径前缀 /files。
 * 负责已生成文件（如 PDF 报告）的列表查询与下载。
 * 文件实际存储在本地磁盘或 MinIO，由 StorageService 抽象；本类只做记录查询与流式回读。
 */
@RestController
@RequestMapping("/files")
public class FileController {
    private final GeneratedFileRepository files;
    /** 文件存储服务（本地或 MinIO 实现，按配置切换） */
    private final StorageService storage;
    /** 当前登录用户（来自 JWT），用于数据隔离 */
    private final CurrentUser current;

    public FileController(
            GeneratedFileRepository files, StorageService storage, CurrentUser current) {
        this.files = files;
        this.storage = storage;
        this.current = current;
    }

    /**
     * GET /files —— 分页列出当前用户的文件列表。
     * 默认按创建时间倒序，每页 20 条。
     */
    @GetMapping
    PageResponse<ResourceDtos.FileResponse> list(
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
                    Pageable pageable) {
        return PageResponse.from(
                files.findByUserIdAndBusinessType(current.id(), "AGENT_TASK", pageable),
                ResourceDtos.FileResponse::from);
    }

    /**
     * GET /files/{id}/download —— 下载指定文件。
     * 先按 ID + 用户查出文件记录（鉴权），再从存储中按 storageKey 读取字节流返回。
     * 通过 Content-Disposition 头触发浏览器下载，文件名做 UTF-8 编码以支持中文。
     */
    @GetMapping("/{id}/download")
    ResponseEntity<byte[]> download(@PathVariable Long id) throws Exception {
        GeneratedFile file =
                files.findByIdAndUserIdAndBusinessType(id, current.id(), "AGENT_TASK")
                        .orElseThrow(() -> ApiException.notFound("文件不存在"));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.getContentType()))
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        // filename*=UTF-8'' 是 RFC 5987 规定的非 ASCII 文件名编码方式
                        "attachment; filename*=UTF-8''"
                                + URLEncoder.encode(file.getFileName(), StandardCharsets.UTF_8))
                .body(storage.read(file.getStorageKey()));
    }
}
