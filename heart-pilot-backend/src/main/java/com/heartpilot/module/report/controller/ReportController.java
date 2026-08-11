package com.heartpilot.module.report.controller;

import com.heartpilot.common.api.PageResponse;
import com.heartpilot.module.file.dto.ResourceDtos;
import com.heartpilot.module.file.entity.GeneratedFile;
import com.heartpilot.module.file.service.StorageService;
import com.heartpilot.module.report.service.impl.ReportService;
import com.heartpilot.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/reports")
public class ReportController {
    private final ReportService service;
    private final CurrentUser current;
    private final StorageService storage;

    public ReportController(ReportService service, CurrentUser current, StorageService storage) {
        this.service = service;
        this.current = current;
        this.storage = storage;
    }

    @GetMapping
    PageResponse<ResourceDtos.ReportResponse> list(
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
                    Pageable pageable) {
        return PageResponse.from(
                service.list(current.id(), pageable), ResourceDtos.ReportResponse::from);
    }

    @GetMapping("/{id}")
    ResourceDtos.ReportResponse get(@PathVariable Long id) {
        return ResourceDtos.ReportResponse.from(service.get(id, current.id()));
    }

    @PostMapping
    ResourceDtos.ReportResponse create(@Valid @RequestBody GenerateRequest request) {
        return ResourceDtos.ReportResponse.from(
                service.generate(request.conversationId(), current.id()));
    }

    @DeleteMapping("/{id}")
    void delete(@PathVariable Long id) {
        service.delete(id, current.id());
    }

    @GetMapping("/{id}/pdf")
    ResponseEntity<byte[]> pdf(@PathVariable Long id) throws Exception {
        GeneratedFile file = service.exportPdf(id, current.id());
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=relationship-report-" + id + ".pdf")
                .body(storage.read(file.getStorageKey()));
    }

    public record GenerateRequest(@NotNull Long conversationId) {}
}
