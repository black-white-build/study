package com.heartpilot.module.conversation.repository;

import com.heartpilot.module.conversation.entity.ConversationContextState;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** 会话记忆状态表的 JPA 仓库。 */
public interface ConversationContextStateRepository
        extends JpaRepository<ConversationContextState, Long> {
    /** 按会话与用户查询唯一一条记忆状态。 */
    Optional<ConversationContextState> findByConversationIdAndUserId(
            Long conversationId, Long userId);

    /** 删除指定会话与用户的记忆状态。 */
    void deleteByConversationIdAndUserId(Long conversationId, Long userId);
}
