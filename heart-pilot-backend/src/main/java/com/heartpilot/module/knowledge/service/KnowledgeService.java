package com.heartpilot.module.knowledge.service;

import com.heartpilot.module.knowledge.entity.KnowledgeDocument;
import com.heartpilot.module.knowledge.entity.enums.KnowledgeEvidenceLevel;
import com.heartpilot.module.knowledge.entity.enums.KnowledgeReviewStatus;
import java.nio.file.Path;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.multipart.MultipartFile;

/**
 * 知识库（RAG）服务接口。 负责管理员上传文档（解析 → 切片 → 向量化写入 PGVector）、按查询做语义检索（带 Redis 缓存与关键词兜底）、
 * 文档列表查询和删除（级联清理向量库与对象存储）。
 */
public interface KnowledgeService {
    /**
     * 上传并处理一份知识文档：校验类型 → 存对象存储 → Tika 解析文本 → 切片 → 向量化写入 PGVector。 任一步失败都会把文档状态置为 FAILED 并记录错误。
     *
     * @param file 上传的文件
     * @param metadata 文档元数据（分类、适用场景、来源、证据等级等）
     * @param userId 上传人 ID
     * @return 已持久化的文档元数据
     */
    KnowledgeDocument upload(MultipartFile file, DocumentMetadata metadata, Long userId);

    /**
     * 按查询语义检索相关切片，优先走 PGVector 向量相似度，未命中或 AI 未开启时降级为关键词匹配。 结果带 Redis 缓存。
     *
     * @param query 用户查询
     * @param limit 返回条数
     * @return 命中的知识来源列表
     */
    List<Source> retrieve(String query, int limit);

    /**
     * 从指定目录全量重建知识库：扫描 Markdown 文件、清空旧文档后重新解析、切片、向量化。
     * 用于以文件仓库为权威来源同步线上知识库。
     *
     * @param sourceDirectory 知识源目录
     * @param userId 操作人 ID
     * @return 本次重建的索引版本与文档/切片统计
     */
    RebuildResult rebuildRepository(Path sourceDirectory, Long userId);

    /** 返回当前已就绪文档的索引版本号，用于缓存键与消息审计。 */
    String currentIndexVersion();

    /** 管理员分页列出全部文档元数据 */
    Page<KnowledgeDocument> list(Pageable pageable);

    /**
     * 读取指定文档已经落库的切片正文，按 chunkIndex 顺序拼接。
     * 该操作只读取 knowledge_chunk，不会重新解析或重新切片原始文件。
     */
    DocumentContent content(Long id);

    /**
     * 审核通过一份已处理完成的文档，使其进入 RAG 检索池。
     * 仅允许 READY 且 IN_REVIEW 的文档执行该状态流转。
     */
    KnowledgeDocument approve(Long id);

    /** 删除文档：级联清理 PGVector 向量、切片记录、对象存储文件、文档元数据 */
    void delete(Long id);

    /** 上传文档时携带的业务元数据，用于分类、场景路由与引用展示。 */
    record DocumentMetadata(
            String category,
            String applicableScenario,
            String relationshipStage,
            String sourceName,
            String sourceUrl,
            String contentVersion,
            KnowledgeReviewStatus reviewStatus,
            String riskTags,
            KnowledgeEvidenceLevel evidenceLevel) {}

    /** 检索命中的知识来源条目：含文档标识、分类、来源、证据等级、相关度与索引版本等引用信息。 */
    record Source(
            Long documentId,
            String documentName,
            String section,
            String content,
            int chunkIndex,
            String category,
            String applicableScenario,
            String relationshipStage,
            String sourceName,
            String sourceUrl,
            String contentVersion,
            String riskTags,
            KnowledgeEvidenceLevel evidenceLevel,
            double relevance,
            String indexVersion) {}

    /** 知识库全量重建结果：索引版本号、文档数与写入的切片数。 */
    record RebuildResult(String indexVersion, int documentCount, int chunkCount) {}

    /** 管理端查看的文档纯文本，由已入库切片按顺序拼接而成。 */
    record DocumentContent(Long documentId, String originalName, int chunkCount, String content) {}
}
