package com.heartpilot.module.conversation.controller;

import com.heartpilot.common.api.PageResponse;
import com.heartpilot.module.conversation.dto.ConversationDtos;
import com.heartpilot.module.conversation.service.impl.ConversationService;
import com.heartpilot.security.CurrentUser;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/conversations")
public class ConversationController {
    private final ConversationService service;
    private final CurrentUser current;

    public ConversationController(ConversationService service, CurrentUser current) {
        this.service = service;
        this.current = current;
    }

    @GetMapping
    PageResponse<ConversationDtos.ConversationResponse> list(
            @PageableDefault(size = 30, sort = "lastMessageAt", direction = Sort.Direction.DESC)
                    Pageable pageable) {
        return PageResponse.from(
                service.list(current.id(), pageable), ConversationDtos.ConversationResponse::from);
    }

    @PostMapping
    ConversationDtos.ConversationResponse create(
            @RequestBody(required = false) ConversationDtos.CreateRequest request) {
        return ConversationDtos.ConversationResponse.from(
                service.create(current.id(), request == null ? null : request.title()));
    }

    @PatchMapping("/{id}")
    ConversationDtos.ConversationResponse rename(
            @PathVariable Long id, @Valid @RequestBody ConversationDtos.RenameRequest request) {
        return ConversationDtos.ConversationResponse.from(
                service.rename(id, current.id(), request.title()));
    }

    @DeleteMapping("/{id}")
    void delete(@PathVariable Long id) {
        service.delete(id, current.id());
    }

    @GetMapping("/{id}/messages")
    PageResponse<ConversationDtos.MessageResponse> history(
            @PathVariable Long id,
            @PageableDefault(size = 100, sort = "createdAt", direction = Sort.Direction.ASC)
                    Pageable pageable) {
        return PageResponse.from(
                service.history(id, current.id(), pageable),
                ConversationDtos.MessageResponse::from);
    }

    @PostMapping(value = "/{id}/messages/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter send(
            @PathVariable Long id, @Valid @RequestBody ConversationDtos.SendRequest request) {
        return service.send(id, current.id(), request.content(), null);
    }

    @PostMapping(
            value = "/{id}/messages/{messageId}/regenerate",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter regenerate(@PathVariable Long id, @PathVariable Long messageId) {
        return service.regenerate(id, messageId, current.id());
    }

    @PostMapping("/{id}/stop")
    Map<String, Boolean> stop(@PathVariable Long id) {
        return Map.of("stopped", service.stop(id, current.id()));
    }
}
