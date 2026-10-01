package com.heartpilot.module.knowledge.entity;

import com.heartpilot.common.entity.BaseEntity;
import com.heartpilot.module.knowledge.entity.enums.KnowledgeDocumentStatus;
import com.heartpilot.module.knowledge.entity.enums.KnowledgeEvidenceLevel;
import com.heartpilot.module.knowledge.entity.enums.KnowledgeReviewStatus;
import jakarta.persistence.*;
import lombok.*;

/**
 * 知识库文档元数据实体，对应数据库表 knowledge_document。 记录管理员上传的原始文件信息（对象存储
 * key、类型、大小）以及处理状态（UPLOADED/PROCESSING/READY/FAILED）。 文档正文切片存放在 knowledge_chunk 表，向量写入 PGVector。 继承
 * BaseEntity 获得 id、创建时间、更新时间等公共字段。
 */
@Entity
@Table(
        name = "knowledge_document",
        indexes = @Index(name = "idx_doc_status", columnList = "status,createdAt"))
@Getter
@Setter
@NoArgsConstructor
public class KnowledgeDocument extends BaseEntity {
    /** 上传人（管理员）用户 ID */
    @Column(nullable = false)
    private Long uploadedBy;

    /** 原始文件名，便于前端展示和溯源 */
    @Column(nullable = false, length = 255)
    private String originalName;

    /** 文件 MIME 类型（text/plain、application/pdf 等），决定解析方式 */
    @Column(nullable = false, length = 100)
    private String contentType;

    /** 文件大小（字节） */
    @Column(nullable = false)
    private long sizeBytes;

    /** 对象存储 key，删除文档时据此清理存储文件 */
    @Column(nullable = false, length = 500)
    private String storageKey;

    /** 文档处理状态，默认 UPLOADED；流转由 KnowledgeServiceImpl 维护 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private KnowledgeDocumentStatus status = KnowledgeDocumentStatus.UPLOADED;

    /** 文档分类标签（如"关系成长"），预留用于后续按类过滤检索 */
    @Column(length = 64)
    private String category;

    /** 适用场景标签（如"通用沟通""冲突修复"），用于按场景过滤检索 */
    @Column(length = 160)
    private String applicableScenario;

    /** 适用关系阶段（如"恋爱期""婚姻期"），辅助精准召回 */
    @Column(length = 64)
    private String relationshipStage;

    /** 资料来源名称（书籍/课程/专家等），便于展示与溯源 */
    @Column(length = 160)
    private String sourceName;

    /** 资料来源链接，可为空 */
    @Column(length = 500)
    private String sourceUrl;

    /** 内容版本号，便于追踪文档迭代与重索引 */
    @Column(nullable = false, length = 40)
    private String contentVersion = "1.0";

    /** 审核状态，仅通过审核的文档可被检索命中 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private KnowledgeReviewStatus reviewStatus = KnowledgeReviewStatus.IN_REVIEW;

    /** 风险标签（逗号分隔），如"自杀""家暴"，用于路由到高风险处理流程 */
    @Column(length = 300)
    private String riskTags;

    /** 证据等级，标注内容可信度（UNVERIFIED/EVIDENCE_BASED 等） */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private KnowledgeEvidenceLevel evidenceLevel = KnowledgeEvidenceLevel.UNVERIFIED;

    /** 文档在源码仓库中的相对路径（若从仓库同步） */
    @Column(length = 500)
    private String repositoryPath;

    /** 内容哈希，用于上传去重与变更检测 */
    @Column(length = 64)
    private String contentHash;

    /** 向量索引版本号，用于灰度切换索引与排查检索差异 */
    @Column(nullable = false, length = 64)
    private String indexVersion = "manual";

    /** 失败时的错误信息（截断 480 字），便于管理员排查 */
    @Column(length = 500)
    private String errorMessage;

    /** 切片总数，处理完成后回填 */
    @Column(nullable = false)
    private int chunkCount;
}
