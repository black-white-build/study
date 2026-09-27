package com.heartpilot.module.knowledge.service.impl;

import com.heartpilot.common.exception.ApiException;
import com.heartpilot.module.agent.service.RedisResultCacheService;
import com.heartpilot.module.file.service.StorageService;
import com.heartpilot.module.knowledge.entity.KnowledgeChunk;
import com.heartpilot.module.knowledge.entity.KnowledgeDocument;
import com.heartpilot.module.knowledge.entity.enums.KnowledgeDocumentStatus;
import com.heartpilot.module.knowledge.repository.KnowledgeChunkRepository;
import com.heartpilot.module.knowledge.repository.KnowledgeDocumentRepository;
import com.heartpilot.module.knowledge.service.KnowledgeService;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.apache.tika.Tika;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * 知识库（RAG）核心服务实现。
 * 完整流程：管理员上传文件 → Tika 抽取纯文本 → 清洗 → 按 1200 字/120 字重叠切片 →
 * 每个切片写入 knowledge_chunk 表并通过 Spring AI VectorStore（PGVector）做向量相似度检索。
 *
 * 设计要点：
 * - 上传后同步处理：先存对象存储落库文档记录，再解析切片；任一步失败把文档状态置为 FAILED
 * - 切片向量化采用批量写入，中途失败时回滚已写入的 PGVector 向量和已保存的 chunk，避免脏数据
 * - 检索走三级策略：Redis 缓存命中 → PGVector 向量相似度（阈值 0.58）→ 关键词包含兜底
 * - AI（DashScope Embedding）未配置或向量检索异常时降级为全表关键词扫描，保证检索可用
 * - 删除文档时先按 vectorId 清理 PGVector，再删 chunk 表、对象存储文件、文档元数据；存储删除失败不阻断元数据删除
 */
@Service
public class KnowledgeServiceImpl implements KnowledgeService {
    /** 允许上传的 MIME 类型白名单，octet-stream 兜底给无类型的 Markdown/TXT */
    private static final Set<String> SUPPORTED_TYPES =
            Set.of(
                    "text/plain",
                    "text/markdown",
                    "application/pdf",
                    "application/msword",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    "application/octet-stream");

    private final KnowledgeDocumentRepository documents;
    private final KnowledgeChunkRepository chunks;
    private final StorageService storage;
    /** PGVector 向量存储，Bean 名 relationshipVectorStore，由 Spring AI 自动配置 */
    private final VectorStore vectors;
    private final MeterRegistry metrics;
    private final RedisResultCacheService cache;
    /** 是否启用 AI Embedding：DashScope key 未配置时为 false，检索降级为关键词匹配 */
    private final boolean aiEnabled;
    /** Apache Tika 统一抽取 PDF/Word/TXT/Markdown 文本 */
    private final Tika tika = new Tika();

    /**
     * 构造器注入。
     * vectors 通过 @Qualifier 指定 relationshipVectorStore（关系知识库专用向量库）；
     * aiEnabled 根据 DashScope key 是否配置决定是否启用向量检索。
     */
    public KnowledgeServiceImpl(
            KnowledgeDocumentRepository documents,
            KnowledgeChunkRepository chunks,
            StorageService storage,
            @Qualifier("relationshipVectorStore") VectorStore vectors,
            MeterRegistry metrics,
            RedisResultCacheService cache,
            @Value("${spring.ai.dashscope.api-key:not-configured}") String key) {
        this.documents = documents;
        this.chunks = chunks;
        this.storage = storage;
        this.vectors = vectors;
        this.metrics = metrics;
        this.cache = cache;
        this.aiEnabled = !key.isBlank() && !"not-configured".equals(key);
    }

    @Override
    public KnowledgeDocument upload(MultipartFile file, String category, Long userId) {
        validate(file);
        KnowledgeDocument document = new KnowledgeDocument();
        document.setUploadedBy(userId);
        document.setOriginalName(
                Optional.ofNullable(file.getOriginalFilename()).orElse("document"));
        document.setContentType(
                Optional.ofNullable(file.getContentType()).orElse("application/octet-stream"));
        document.setSizeBytes(file.getSize());
        document.setCategory(category);

        try {
            // 先把原始文件落到对象存储，再做解析切片，保证元数据先落库
            StorageService.StoredObject stored = storage.store(file, "knowledge");
            document.setStorageKey(stored.key());
            document = documents.saveAndFlush(document);
            try (InputStream input = file.getInputStream()) {
                process(document, input);
            }
            metrics.counter("heartpilot.rag.documents", "outcome", "ready").increment();
            return document;
        } catch (Exception exception) {
            // 解析/向量化失败：标记 FAILED 并记录错误，文档元数据保留便于管理员排查
            document.setStatus(KnowledgeDocumentStatus.FAILED);
            document.setErrorMessage(shorten(exception.getMessage(), 480));
            if (document.getStorageKey() != null) documents.save(document);
            metrics.counter("heartpilot.rag.documents", "outcome", "failed").increment();
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "DOCUMENT_PARSE_FAILED",
                    "文档解析失败：" + Optional.ofNullable(exception.getMessage()).orElse("未知错误"));
        }
    }

    /**
     * 文档处理主流程：Tika 抽文本 → 清洗 → 切片 → 逐片写 chunk 表 + PGVector。
     * 中途失败时回滚已写入的向量和已保存的 chunk，避免脏数据。
     */
    private void process(KnowledgeDocument document, InputStream input) throws Exception {
        document.setStatus(KnowledgeDocumentStatus.PROCESSING);
        document.setErrorMessage(null);
        documents.save(document);
        // Tika 自动识别 PDF/Word/TXT/Markdown 并抽取纯文本
        String text = clean(tika.parseToString(input));
        if (text.isBlank()) throw new IllegalArgumentException("文档未解析出有效文本");
        // 切片：每片 1200 字，相邻片重叠 120 字，保持上下文连贯
        List<String> pieces = split(text, 1_200, 120);
        List<String> writtenVectorIds = new ArrayList<>();
        try {
            for (int index = 0; index < pieces.size(); index++) {
                String piece = pieces.get(index);
                KnowledgeChunk chunk = new KnowledgeChunk();
                chunk.setDocumentId(document.getId());
                chunk.setChunkIndex(index);
                chunk.setContent(piece);
                chunk.setSectionTitle(section(piece, index));
                chunk.setKeywords(keywords(piece));
                // 粗估 token 数：中文约 3 字符/token
                chunk.setTokenCount(Math.max(1, piece.length() / 3));
                // 向量元数据随片写入 PGVector，检索时据此回填文档名/章节/序号
                Map<String, Object> metadata =
                        Map.of(
                                "documentId",
                                document.getId(),
                                "documentName",
                                document.getOriginalName(),
                                "section",
                                chunk.getSectionTitle(),
                                "chunkIndex",
                                index);
                Document vectorDocument = new Document(piece, metadata);
                // 写入 PGVector，vectorDocument.getId() 即向量主键，后续删除用
                vectors.add(List.of(vectorDocument));
                writtenVectorIds.add(vectorDocument.getId());
                chunk.setVectorId(vectorDocument.getId());
                chunks.save(chunk);
            }
        } catch (Exception exception) {
            // 回滚：删除已写入 PGVector 的向量和已保存的 chunk 记录
            if (!writtenVectorIds.isEmpty()) vectors.delete(writtenVectorIds);
            chunks.deleteByDocumentId(document.getId());
            throw exception;
        }
        document.setChunkCount(pieces.size());
        document.setStatus(KnowledgeDocumentStatus.READY);
        documents.save(document);
    }

    @Override
    public List<Source> retrieve(String query, int limit) {
        // Redis 缓存：key=query|limit，命中直接返回，避免重复向量检索
        String cacheKey = query.strip() + "|" + limit;
        Optional<Source[]> cached = cache.getKnowledge(cacheKey, Source[].class);
        if (cached.isPresent()) {
            metrics.counter("heartpilot.rag.retrieval", "strategy", "redis_cache").increment();
            return List.of(cached.get());
        }
        List<Source> result = retrieveUncached(query, limit);
        cache.putKnowledge(cacheKey, result);
        return result;
    }

    /**
     * 未命中缓存时的实际检索：优先 PGVector 语义相似度，失败/未命中降级为关键词包含。
     */
    private List<Source> retrieveUncached(String query, int limit) {
        if (aiEnabled) {
            try {
                // PGVector 向量相似度检索：topK=limit，相似度阈值 0.58 过滤弱相关
                List<Document> matches =
                        vectors.similaritySearch(
                                SearchRequest.builder()
                                        .query(query)
                                        .topK(limit)
                                        .similarityThreshold(0.58)
                                        .build());
                if (matches != null && !matches.isEmpty()) {
                    metrics.counter("heartpilot.rag.retrieval", "strategy", "vector").increment();
                    // 从向量元数据回填文档名、章节、切片序号
                    return matches.stream()
                            .map(
                                    document ->
                                            new Source(
                                                    String.valueOf(
                                                            document.getMetadata()
                                                                    .getOrDefault(
                                                                            "documentName",
                                                                            "知识文档")),
                                                    String.valueOf(
                                                            document.getMetadata()
                                                                    .getOrDefault(
                                                                            "section", "相关片段")),
                                                    document.getText(),
                                                    Integer.parseInt(
                                                            String.valueOf(
                                                                    document.getMetadata()
                                                                            .getOrDefault(
                                                                                    "chunkIndex",
                                                                                    0)))))
                            .toList();
                }
            } catch (Exception ignored) {
                // 向量检索异常（如 Embedding 服务不可用）：打点后降级到关键词匹配
                metrics.counter("heartpilot.rag.retrieval_failures", "strategy", "vector")
                        .increment();
            }
        }
        // 关键词兜底：全表扫描 chunk，按查询词逐个匹配 content，去重后取 limit 条
        metrics.counter("heartpilot.rag.retrieval", "strategy", "keyword_fallback").increment();
        LinkedHashMap<Long, KnowledgeChunk> found = new LinkedHashMap<>();
        List<KnowledgeChunk> all = chunks.findAll();
        for (String term : terms(query)) {
            for (KnowledgeChunk chunk : all) {
                if (chunk.getContent().toLowerCase().contains(term)) {
                    found.putIfAbsent(chunk.getId(), chunk);
                    if (found.size() >= limit) break;
                }
            }
            if (found.size() >= limit) break;
        }
        // 回填所属文档名
        List<Source> result = new ArrayList<>();
        for (KnowledgeChunk chunk : found.values()) {
            KnowledgeDocument document = documents.findById(chunk.getDocumentId()).orElse(null);
            if (document != null) {
                result.add(
                        new Source(
                                document.getOriginalName(),
                                chunk.getSectionTitle(),
                                chunk.getContent(),
                                chunk.getChunkIndex()));
            }
        }
        return result;
    }

    @Override
    public Page<KnowledgeDocument> list(Pageable pageable) {
        return documents.findAll(pageable);
    }

    @Transactional
    @Override
    public void delete(Long id) {
        KnowledgeDocument document =
                documents.findById(id).orElseThrow(() -> ApiException.notFound("文档不存在"));
        // 先收集该文档所有切片的 PGVector vectorId，批量删除向量
        List<String> vectorIds =
                chunks.findByDocumentIdOrderByChunkIndexAsc(id).stream()
                        .map(KnowledgeChunk::getVectorId)
                        .filter(Objects::nonNull)
                        .toList();
        if (!vectorIds.isEmpty()) vectors.delete(vectorIds);
        chunks.deleteByDocumentId(id);
        try {
            storage.delete(document.getStorageKey());
        } catch (IOException ignored) {
            // Metadata deletion remains deterministic; storage cleanup can be retried separately.
            // 存储删除失败不阻断元数据删除，避免孤立文件阻塞清理任务
        }
        documents.delete(document);
    }

    /** 上传前校验：文件非空且 MIME 类型在白名单内 */
    private void validate(MultipartFile file) {
        if (file.isEmpty()) throw ApiException.badRequest("文件不能为空");
        String type = Optional.ofNullable(file.getContentType()).orElse("application/octet-stream");
        if (!SUPPORTED_TYPES.contains(type)) {
            throw ApiException.badRequest("仅支持 Markdown、TXT、PDF 和 Word 文件");
        }
    }

    /** 清洗抽取文本：去 NUL 字节、压缩多余空白和连续空行，提升切片质量 */
    private String clean(String value) {
        return value.replace("\u0000", "")
                .replaceAll("[\\t\\x0B\\f\\r]+", " ")
                .replaceAll(" *\\n *", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }

    /** 按固定窗口切片：每片 size 字符，相邻片重叠 overlap 字符，保持上下文连贯 */
    private List<String> split(String text, int size, int overlap) {
        List<String> result = new ArrayList<>();
        for (int start = 0; start < text.length(); start += size - overlap) {
            int end = Math.min(text.length(), start + size);
            result.add(text.substring(start, end));
            if (end == text.length()) break;
        }
        return result;
    }

    /** 从切片中提取 Markdown 一级标题作为章节名，无标题时用"第 N 节"兜底 */
    private String section(String text, int index) {
        return text.lines()
                .filter(line -> line.startsWith("#"))
                .findFirst()
                .map(line -> line.replaceFirst("^#+\\s*", ""))
                .orElse("第 " + (index + 1) + " 节");
    }

    /** 提取切片关键词（最多 10 个，逗号拼接），用于关键词兜底检索 */
    private String keywords(String text) {
        return terms(text).stream()
                .limit(10)
                .reduce((left, right) -> left + "," + right)
                .orElse("");
    }

    /** 按非字母数字字符切词，小写、去重、取长度>=2 的前 20 个词 */
    private List<String> terms(String text) {
        return Pattern.compile("[^\\p{L}\\p{N}]+")
                .splitAsStream(text.toLowerCase())
                .filter(value -> value.length() >= 2)
                .distinct()
                .limit(20)
                .toList();
    }

    /** 截断字符串到指定长度，null 返回 null，用于错误信息存储前的长度控制 */
    private String shorten(String value, int length) {
        if (value == null) return null;
        return value.substring(0, Math.min(value.length(), length));
    }
}
