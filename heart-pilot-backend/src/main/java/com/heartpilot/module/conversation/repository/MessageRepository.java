package com.heartpilot.module.conversation.repository;

import com.heartpilot.module.conversation.entity.AiMessage;
import java.time.Instant;
import java.util.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MessageRepository extends JpaRepository<AiMessage, Long> {
    List<AiMessage> findByConversationIdAndUserIdOrderByCreatedAtAsc(
            Long conversationId, Long userId);

    Page<AiMessage> findByConversationIdAndUserId(
            Long conversationId, Long userId, Pageable pageable);

    List<AiMessage> findTop30ByUserIdOrderByCreatedAtDesc(Long userId);

    Optional<AiMessage> findByIdAndUserId(Long id, Long userId);

    void deleteByConversationIdAndUserId(Long conversationId, Long userId);

    List<AiMessage> findByUserIdAndCreatedAtBetweenOrderByCreatedAtAsc(
            Long userId, Instant start, Instant end);
}
