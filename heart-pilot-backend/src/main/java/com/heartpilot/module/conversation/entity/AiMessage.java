package com.heartpilot.module.conversation.entity;

import com.heartpilot.common.entity.BaseEntity;
import com.heartpilot.module.conversation.entity.enums.AiMessageStatus;
import jakarta.persistence.*;
import lombok.*;

/**
 * AI 对话消息实体，对应数据库表 ai_message。
 * 一条消息是会话中的一次发言（用户提问或 AI 回复），并记录 token 用量、成本、来源引用等可观测数据。
 * 继承 BaseEntity 获得 id、创建时间等公共字段。
 */
@Entity
@Table(
        name = "ai_message",
        indexes = {@Index(name = "idx_msg_conversation", columnList = "conversationId,createdAt")})
@Getter
@Setter
@NoArgsConstructor
public class AiMessage extends BaseEntity {
    /** 所属会话 ID */
    @Column(nullable = false)
    private Long conversationId;

    /** 所属用户 ID，冗余存储便于按用户维度查询与鉴权 */
    @Column(nullable = false)
    private Long userId;

    /** 消息角色：user=用户提问，assistant=AI 回复 */
    @Column(nullable = false, length = 16)
    private String role;

    /** 消息正文内容，TEXT 类型支持长回复 */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    /** 本次请求输入 token 数（计费与观测用） */
    @Column(nullable = false)
    private int inputTokens;

    /** 本次请求输出 token 数 */
    @Column(nullable = false)
    private int outputTokens;

    /** 消息生成状态：流式中→完成/失败/取消，由对话服务在流式过程中更新 */
    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private AiMessageStatus status = AiMessageStatus.COMPLETED;

    /** 生成该回复所用的模型名称 */
    @Column(length = 80)
    private String model;

    /** 生成失败时的错误信息，正常完成时为 null */
    @Column(length = 500)
    private String errorMessage;

    /** 检索增强来源引用的 JSON 快照（链接、标题等） */
    @Column(length = 1000)
    private String sourcesJson;

    /** 若为"重新生成"产生的新消息，指向被替换的原消息 ID */
    private Long regeneratedFromId;

    /** 本次请求是否命中了上下文缓存（命中可降本） */
    @Column(nullable = false)
    private boolean cacheHit;

    /** 模型提供商整体响应耗时（毫秒） */
    private Long providerLatencyMs;

    /** 输入成本，单位微元（1e-6），用整数避免浮点误差 */
    @Column(nullable = false)
    private long inputCostMicros;

    /** 输出成本，单位微元 */
    @Column(nullable = false)
    private long outputCostMicros;

    /** 估算总成本（输入+输出），单位微元 */
    @Column(nullable = false)
    private long estimatedCostMicros;

    /** 因命中缓存而节省的成本，单位微元 */
    @Column(nullable = false)
    private long cacheSavedCostMicros;
}
