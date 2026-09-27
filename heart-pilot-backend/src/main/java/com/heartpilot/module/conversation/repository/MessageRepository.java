package com.heartpilot.module.conversation.repository;

import com.heartpilot.module.conversation.entity.AiMessage;
import java.time.Instant;
import java.util.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 消息（AiMessage）数据访问层。
 */
public interface MessageRepository extends JpaRepository<AiMessage, Long> {
    /** 按时间正序取出某会话下某用户的全部消息，用于拼接上下文 */
    List<AiMessage> findByConversationIdAndUserIdOrderByCreatedAtAsc(
            Long conversationId, Long userId);

    /** 分页查询某会话下某用户的消息历史 */
    Page<AiMessage> findByConversationIdAndUserId(
            Long conversationId, Long userId, Pageable pageable);

    /** 取某用户最近 30 条消息，用于上下文截断/统计等场景 */
    List<AiMessage> findTop30ByUserIdOrderByCreatedAtDesc(Long userId);

    /** 按 ID + 用户 ID 查询单条消息，用于鉴权与重新生成定位 */
    Optional<AiMessage> findByIdAndUserId(Long id, Long userId);

    /** 删除某会话下某用户的全部消息（级联清理） */
    void deleteByConversationIdAndUserId(Long conversationId, Long userId);

    /** 查询某用户在指定时间区间内的消息，按时间正序，用于用量/成本统计 */
    List<AiMessage> findByUserIdAndCreatedAtBetweenOrderByCreatedAtAsc(
            Long userId, Instant start, Instant end);
}
