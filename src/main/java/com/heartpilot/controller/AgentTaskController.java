package com.heartpilot.controller;

import com.heartpilot.domain.GeneratedFile;
import com.heartpilot.dto.AgentTaskDtos;
import com.heartpilot.dto.PageResponse;
import com.heartpilot.security.CurrentUser;
import com.heartpilot.service.AgentTaskService;
import com.heartpilot.service.RouteMapService;
import com.heartpilot.service.StorageService;
import jakarta.validation.Valid;
import java.util.List;
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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/agent-tasks")
public class AgentTaskController {
    private final AgentTaskService service;
    private final CurrentUser current;
    private final StorageService storage;
    private final RouteMapService routeMaps;

    public AgentTaskController(
            AgentTaskService service,
            CurrentUser current,
            StorageService storage,
            RouteMapService routeMaps) {
        this.service = service;
        this.current = current;
        this.storage = storage;
        this.routeMaps = routeMaps;
    }

    @GetMapping
    PageResponse<AgentTaskDtos.TaskResponse> list(
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
                    Pageable pageable) {
        return PageResponse.from(
                service.list(current.id(), pageable), AgentTaskDtos.TaskResponse::from);
    }

    @GetMapping("/{id}")
    AgentTaskDtos.TaskDetailResponse get(@PathVariable Long id) {
        AgentTaskService.TaskDetail detail = service.get(id, current.id());
        return new AgentTaskDtos.TaskDetailResponse(
                AgentTaskDtos.TaskResponse.from(detail.task()),
                detail.steps().stream().map(AgentTaskDtos.StepResponse::from).toList(),
                detail.toolCalls().stream().map(AgentTaskDtos.ToolCallResponse::from).toList(),
                detail.executionEvents().stream()
                        .map(AgentTaskDtos.ExecutionEventResponse::from)
                        .toList(),
                AgentTaskDtos.FileResponse.from(detail.pdfFile()));
    }

    @GetMapping("/{id}/execution-events")
    List<AgentTaskDtos.ExecutionEventResponse> executionEvents(@PathVariable Long id) {
        return service.get(id, current.id()).executionEvents().stream()
                .map(AgentTaskDtos.ExecutionEventResponse::from)
                .toList();
    }

    @GetMapping("/{id}/route-map")
    ResponseEntity<byte[]> routeMap(@PathVariable Long id) {
        RouteMapService.RouteMapImage image =
                routeMaps.render(service.get(id, current.id()).task());
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(image.contentType()))
                .cacheControl(
                        org.springframework.http.CacheControl.maxAge(
                                        java.time.Duration.ofMinutes(5))
                                .cachePrivate())
                .body(image.bytes());
    }

    @PostMapping
    AgentTaskDtos.TaskResponse create(
            @Valid @RequestBody AgentTaskDtos.CreateRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return AgentTaskDtos.TaskResponse.from(
                service.create(
                        current.id(),
                        request.title(),
                        request.objective(),
                        request.parameters(),
                        idempotencyKey));
    }

    @PostMapping(value = "/{id}/run", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter run(@PathVariable Long id) {
        return service.run(id, current.id());
    }

    @PostMapping(value = "/{id}/confirm", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter confirm(
            @PathVariable Long id, @Valid @RequestBody AgentTaskDtos.ConfirmRequest request) {
        return service.confirm(
                id,
                current.id(),
                request.approved(),
                request.note(),
                request.city(),
                request.budget(),
                request.questions());
    }

    @PostMapping("/{id}/cancel")
    AgentTaskDtos.TaskResponse cancel(@PathVariable Long id) {
        return AgentTaskDtos.TaskResponse.from(service.cancel(id, current.id()));
    }

    @DeleteMapping("/{id}")
    void delete(@PathVariable Long id) {
        service.delete(id, current.id());
    }

    @PostMapping("/{id}/pdf")
    AgentTaskDtos.FileResponse generatePdf(@PathVariable Long id) {
        return AgentTaskDtos.FileResponse.from(service.generatePdf(id, current.id()));
    }

    @GetMapping("/{id}/pdf")
    ResponseEntity<byte[]> downloadPdf(@PathVariable Long id) throws Exception {
        GeneratedFile file = service.getPdf(id, current.id());
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=action-plan-" + id + ".pdf")
                .body(storage.read(file.getStorageKey()));
    }
}
