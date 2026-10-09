package com.heartpilot.module.conversation.service;

import com.heartpilot.module.conversation.entity.AiConversation;
import com.heartpilot.module.conversation.entity.AiMessage;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 对话服务接口。 负责会话的 CRUD、消息历史查询，以及基于 SSE 的流式问答、重新生成与停止生成。 */
public interface ConversationService {
    /** 分页查询某用户未归档的会话列表 */
    Page<AiConversation> list(Long userId, Pageable pageable);

    /** 创建会话，标题为空时由实现自动生成 */
    AiConversation create(Long userId, String title);

    /** 按 ID 取会话（带用户鉴权），不存在抛 404 */
    AiConversation get(Long id, Long userId);

    /** 取某会话的全量消息（不分页，按时间正序） */
    List<AiMessage> history(Long id, Long userId);

    /** 分页取某会话的消息历史 */
    Page<AiMessage> history(Long id, Long userId, Pageable pageable);

    /** 重命名会话标题，仅所有者可操作 */
    AiConversation rename(Long id, Long userId, String title);

    /** 删除会话及其全部消息，仅所有者可操作 */
    void delete(Long id, Long userId);

    /**
     * 发送用户消息并以 SSE 流式返回 AI 回复。
     *
     * @param regeneratedFrom 若为重生成，指向被替换的原消息 ID，否则为 null
     * @return SSE 发射器，前端逐字接收回复
     */
    SseEmitter send(Long conversationId, Long userId, String content, Long regeneratedFrom);

    /** 基于上一条用户消息重新生成 AI 回复，通过 SSE 流式推送 */
    SseEmitter regenerate(Long conversationId, Long messageId, Long userId);

    /** 中止当前正在流式生成的回复，返回是否成功停止 */
    boolean stop(Long conversationId, Long userId);
}
