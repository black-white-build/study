package com.heartpilot.module.conversation.controller;

import com.heartpilot.common.api.PageResponse;
import com.heartpilot.module.conversation.dto.ConversationDtos;
import com.heartpilot.module.conversation.service.ConversationService;
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

/**
 * AI 对话控制器，路径前缀 /conversations。
 * 负责会话（对话）的 CRUD、历史消息查询，以及通过 SSE 发起流式问答、重新生成、停止生成。
 * 当前登录用户身份由 CurrentUser 解析（来自 JWT），所有数据按用户维度隔离。
 */
@RestController
@RequestMapping("/conversations")
public class ConversationController {
    private final ConversationService service;
    /** 当前登录用户信息（由 JWT 过滤器填充），用于鉴权与数据隔离 */
    private final CurrentUser current;

    public ConversationController(ConversationService service, CurrentUser current) {
        this.service = service;
        this.current = current;
    }

    /**
     * GET /conversations —— 分页查询当前用户的会话列表。
     * 默认按最近消息时间倒序，每页 30 条。
     */
    @GetMapping
    PageResponse<ConversationDtos.ConversationResponse> list(
            @PageableDefault(size = 30, sort = "lastMessageAt", direction = Sort.Direction.DESC)
                    Pageable pageable) {
        return PageResponse.from(
                service.list(current.id(), pageable), ConversationDtos.ConversationResponse::from);
    }

    /**
     * POST /conversations —— 创建新会话。
     * 请求体可选，不传标题时由服务端自动生成默认标题。
     */
    @PostMapping
    ConversationDtos.ConversationResponse create(
            @RequestBody(required = false) ConversationDtos.CreateRequest request) {
        return ConversationDtos.ConversationResponse.from(
                service.create(current.id(), request == null ? null : request.title()));
    }

    /**
     * PATCH /conversations/{id} —— 重命名会话标题。
     * 仅会话所有者可操作，由 Service 层鉴权。
     */
    @PatchMapping("/{id}")
    ConversationDtos.ConversationResponse rename(
            @PathVariable Long id, @Valid @RequestBody ConversationDtos.RenameRequest request) {
        return ConversationDtos.ConversationResponse.from(
                service.rename(id, current.id(), request.title()));
    }

    /**
     * DELETE /conversations/{id} —— 删除会话及其全部消息，仅所有者可操作。
     */
    @DeleteMapping("/{id}")
    void delete(@PathVariable Long id) {
        service.delete(id, current.id());
    }

    /**
     * GET /conversations/{id}/messages —— 分页查询某会话的历史消息。
     * 默认按创建时间正序（旧→新），每页 100 条。
     */
    @GetMapping("/{id}/messages")
    PageResponse<ConversationDtos.MessageResponse> history(
            @PathVariable Long id,
            @PageableDefault(size = 100, sort = "createdAt", direction = Sort.Direction.ASC)
                    Pageable pageable) {
        return PageResponse.from(
                service.history(id, current.id(), pageable),
                ConversationDtos.MessageResponse::from);
    }

    /**
     * POST /conversations/{id}/messages/stream —— 发送一条用户消息并以 SSE 流式返回 AI 回复。
     * produces 指定为 text/event-stream，前端通过该流式连接逐字接收 AI 生成内容。
     */
    @PostMapping(value = "/{id}/messages/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter send(
            @PathVariable Long id, @Valid @RequestBody ConversationDtos.SendRequest request) {
        return service.send(id, current.id(), request.content(), null);
    }

    /**
     * POST /conversations/{id}/messages/{messageId}/regenerate —— 重新生成某条 AI 回复。
     * 基于上一条用户消息重新请求模型，结果仍通过 SSE 流式推送。
     */
    @PostMapping(
            value = "/{id}/messages/{messageId}/regenerate",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter regenerate(@PathVariable Long id, @PathVariable Long messageId) {
        return service.regenerate(id, messageId, current.id());
    }

    /**
     * POST /conversations/{id}/stop —— 中止当前正在流式生成的回复。
     * 返回是否成功停止。
     */
    @PostMapping("/{id}/stop")
    Map<String, Boolean> stop(@PathVariable Long id) {
        return Map.of("stopped", service.stop(id, current.id()));
    }
}
