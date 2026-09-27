package com.heartpilot.module.conversation.entity;

import com.heartpilot.common.entity.BaseEntity;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.*;

/**
 * AI 对话会话实体，对应数据库表 ai_conversation。
 * 一条会话代表用户与 AI 的一轮连续对话，下挂多条消息（AiMessage）。
 * 继承 BaseEntity 获得 id、创建时间、更新时间等公共字段。
 */
@Entity
@Table(
        name = "ai_conversation",
        indexes = {@Index(name = "idx_conv_user_updated", columnList = "userId,updatedAt")})
@Getter
@Setter
@NoArgsConstructor
public class AiConversation extends BaseEntity {
    /** 会话所属用户 ID */
    @Column(nullable = false)
    private Long userId;

    /** 会话标题，默认取首条消息摘要，最长 120 字 */
    @Column(nullable = false, length = 120)
    private String title;

    /** 该会话使用的大模型名称，默认 qwen-plus */
    @Column(nullable = false, length = 40)
    private String model = "qwen-plus";

    /** 发送给模型时携带的最大历史消息条数，超出则截断以控制上下文长度 */
    @Column(nullable = false)
    private int contextLimit = 20;

    /** 是否归档（软删除/隐藏），true 表示不在列表中展示 */
    @Column(nullable = false)
    private boolean archived = false;

    /** 最近一条消息时间，会话列表按此倒序排序 */
    private Instant lastMessageAt;
}
