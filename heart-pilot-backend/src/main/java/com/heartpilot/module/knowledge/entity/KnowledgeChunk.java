package com.heartpilot.module.knowledge.entity;

import com.heartpilot.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

/**
 * 知识库文档切片实体，对应数据库表 knowledge_chunk。
 * 一份文档上传后被切分为多个 chunk，每个 chunk 单独向量化写入 PGVector，
 * vectorId 对应 VectorStore 中的文档主键，用于删除时精确清理向量。
 * 通过 (documentId, chunkIndex) 唯一约束保证切片顺序不重复。
 * 继承 BaseEntity 获得 id、创建时间、更新时间等公共字段。
 */
@Entity
@Table(
        name = "knowledge_chunk",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_doc_chunk",
                        columnNames = {"documentId", "chunkIndex"}))
@Getter
@Setter
@NoArgsConstructor
public class KnowledgeChunk extends BaseEntity {
    /** 所属文档 ID，关联 knowledge_document.id */
    @Column(nullable = false)
    private Long documentId;

    /** 切片在原文档中的序号（从 0 开始），与 documentId 组成唯一键 */
    @Column(nullable = false)
    private int chunkIndex;

    /** 切片正文文本，TEXT 类型 */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    /** 从切片中提取的关键词（逗号分隔，最多 10 个），用于关键词兜底检索 */
    @Column(length = 500)
    private String keywords;

    /** 切片所属章节标题（Markdown # 标题），无标题时用"第 N 节"兜底 */
    @Column(length = 160)
    private String sectionTitle;

    /** 该切片在 PGVector 中的文档主键，删除文档时据此向量删除 */
    @Column(length = 100)
    private String vectorId;

    /** 估算 token 数（按字符数/3 粗算），用于切片质量监控 */
    @Column(nullable = false)
    private int tokenCount;
}
