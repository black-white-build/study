package com.heartpilot.module.conversation.repository;

import com.heartpilot.module.conversation.entity.AiConversation;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** 会话（AiConversation）数据访问层。 */
public interface ConversationRepository extends JpaRepository<AiConversation, Long> {
    /** 分页查询某用户未归档的会话列表 */
    Page<AiConversation> findByUserIdAndArchivedFalse(Long userId, Pageable pageable);

    /** 按 ID + 用户 ID 查询会话，用于鉴权（只能访问自己的会话） */
    Optional<AiConversation> findByIdAndUserId(Long id, Long userId);
}
