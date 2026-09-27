package com.heartpilot.module.knowledge.service;

import com.heartpilot.module.knowledge.entity.KnowledgeDocument;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.multipart.MultipartFile;

/**
 * 知识库（RAG）服务接口。
 * 负责管理员上传文档（解析 → 切片 → 向量化写入 PGVector）、按查询做语义检索（带 Redis 缓存与关键词兜底）、
 * 文档列表查询和删除（级联清理向量库与对象存储）。
 */
public interface KnowledgeService {
    /**
     * 上传并处理一份知识文档：校验类型 → 存对象存储 → Tika 解析文本 → 切片 → 向量化写入 PGVector。
     * 任一步失败都会把文档状态置为 FAILED 并记录错误。
     * @param file 上传的文件
     * @param category 文档分类标签
     * @param userId 上传人 ID
     * @return 已持久化的文档元数据
     */
    KnowledgeDocument upload(MultipartFile file, String category, Long userId);

    /**
     * 按查询语义检索相关切片，优先走 PGVector 向量相似度，未命中或 AI 未开启时降级为关键词匹配。
     * 结果带 Redis 缓存。
     * @param query 用户查询
     * @param limit 返回条数
     * @return 命中的知识来源列表
     */
    List<Source> retrieve(String query, int limit);

    /** 管理员分页列出全部文档元数据 */
    Page<KnowledgeDocument> list(Pageable pageable);

    /** 删除文档：级联清理 PGVector 向量、切片记录、对象存储文件、文档元数据 */
    void delete(Long id);

    /** 检索命中的知识来源条目：文档名、章节、正文切片、切片序号 */
    public record Source(String documentName, String section, String content, int chunkIndex) {}
}
