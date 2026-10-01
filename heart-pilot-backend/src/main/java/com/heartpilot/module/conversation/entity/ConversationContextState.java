package com.heartpilot.module.conversation.entity;

import com.heartpilot.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 会话结构化记忆，按会话隔离，不会跨会话共享。 */
@Entity
@Table(
        name = "conversation_context_state",
        uniqueConstraints = @UniqueConstraint(name = "uk_context_conversation", columnNames = "conversationId"))
@Getter
@Setter
@NoArgsConstructor
public class ConversationContextState extends BaseEntity {
    /** 所属会话 ID，唯一约束保证每个会话只有一条记忆。 */
    @Column(nullable = false)
    private Long conversationId;

    /** 所属用户 ID。 */
    @Column(nullable = false)
    private Long userId;

    /** 已确认事实列表的 JSON 数组。 */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String factsJson = "[]";

    /** 用户猜测/推测类假设列表的 JSON 数组。 */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String assumptionsJson = "[]";

    /** 用户偏好列表的 JSON 数组。 */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String preferencesJson = "[]";

    /** 用户情绪列表的 JSON 数组。 */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String emotionsJson = "[]";
}
