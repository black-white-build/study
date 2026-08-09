package com.heartpilot.controller;

import com.heartpilot.domain.GeneratedFile;
import com.heartpilot.dto.PageResponse;
import com.heartpilot.dto.ResourceDtos;
import com.heartpilot.repository.GeneratedFileRepository;
import com.heartpilot.security.CurrentUser;
import com.heartpilot.service.StorageService;
import com.heartpilot.web.ApiException;
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

@RestController
@RequestMapping("/files")
public class FileController {
    private final GeneratedFileRepository files;
    private final StorageService storage;
    private final CurrentUser current;

    public FileController(
            GeneratedFileRepository files, StorageService storage, CurrentUser current) {
        this.files = files;
        this.storage = storage;
        this.current = current;
    }

    @GetMapping
    PageResponse<ResourceDtos.FileResponse> list(
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
                    Pageable pageable) {
        return PageResponse.from(
                files.findByUserId(current.id(), pageable), ResourceDtos.FileResponse::from);
    }

    @GetMapping("/{id}/download")
    ResponseEntity<byte[]> download(@PathVariable Long id) throws Exception {
        GeneratedFile file =
                files.findByIdAndUserId(id, current.id())
                        .orElseThrow(() -> ApiException.notFound("文件不存在"));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.getContentType()))
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename*=UTF-8''"
                                + URLEncoder.encode(file.getFileName(), StandardCharsets.UTF_8))
                .body(storage.read(file.getStorageKey()));
    }
}
