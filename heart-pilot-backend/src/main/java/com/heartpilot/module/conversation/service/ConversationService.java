package com.heartpilot.module.conversation.service;

import com.heartpilot.module.conversation.entity.AiConversation;
import com.heartpilot.module.conversation.entity.AiMessage;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public interface ConversationService {
    Page<AiConversation> list(Long userId, Pageable pageable);

    AiConversation create(Long userId, String title);

    AiConversation get(Long id, Long userId);

    List<AiMessage> history(Long id, Long userId);

    Page<AiMessage> history(Long id, Long userId, Pageable pageable);

    AiConversation rename(Long id, Long userId, String title);

    void delete(Long id, Long userId);

    SseEmitter send(Long conversationId, Long userId, String content, Long regeneratedFrom);

    SseEmitter regenerate(Long conversationId, Long messageId, Long userId);

    boolean stop(Long conversationId, Long userId);
}
