package com.heartpilot.module.knowledge.service.impl;

import com.heartpilot.common.exception.ApiException;
import com.heartpilot.module.agent.service.impl.RedisResultCacheService;
import com.heartpilot.module.file.service.StorageService;
import com.heartpilot.module.knowledge.entity.KnowledgeChunk;
import com.heartpilot.module.knowledge.entity.KnowledgeDocument;
import com.heartpilot.module.knowledge.entity.enums.KnowledgeDocumentStatus;
import com.heartpilot.module.knowledge.repository.KnowledgeChunkRepository;
import com.heartpilot.module.knowledge.repository.KnowledgeDocumentRepository;
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

@Service
public class KnowledgeService {
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
    private final VectorStore vectors;
    private final MeterRegistry metrics;
    private final RedisResultCacheService cache;
    private final boolean aiEnabled;
    private final Tika tika = new Tika();

    public KnowledgeService(
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
            StorageService.StoredObject stored = storage.store(file, "knowledge");
            document.setStorageKey(stored.key());
            document = documents.saveAndFlush(document);
            try (InputStream input = file.getInputStream()) {
                process(document, input);
            }
            metrics.counter("heartpilot.rag.documents", "outcome", "ready").increment();
            return document;
        } catch (Exception exception) {
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

    private void process(KnowledgeDocument document, InputStream input) throws Exception {
        document.setStatus(KnowledgeDocumentStatus.PROCESSING);
        document.setErrorMessage(null);
        documents.save(document);
        String text = clean(tika.parseToString(input));
        if (text.isBlank()) throw new IllegalArgumentException("文档未解析出有效文本");
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
                chunk.setTokenCount(Math.max(1, piece.length() / 3));
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
                vectors.add(List.of(vectorDocument));
                writtenVectorIds.add(vectorDocument.getId());
                chunk.setVectorId(vectorDocument.getId());
                chunks.save(chunk);
            }
        } catch (Exception exception) {
            if (!writtenVectorIds.isEmpty()) vectors.delete(writtenVectorIds);
            chunks.deleteByDocumentId(document.getId());
            throw exception;
        }
        document.setChunkCount(pieces.size());
        document.setStatus(KnowledgeDocumentStatus.READY);
        documents.save(document);
    }

    public List<Source> retrieve(String query, int limit) {
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

    private List<Source> retrieveUncached(String query, int limit) {
        if (aiEnabled) {
            try {
                List<Document> matches =
                        vectors.similaritySearch(
                                SearchRequest.builder()
                                        .query(query)
                                        .topK(limit)
                                        .similarityThreshold(0.58)
                                        .build());
                if (matches != null && !matches.isEmpty()) {
                    metrics.counter("heartpilot.rag.retrieval", "strategy", "vector").increment();
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
                metrics.counter("heartpilot.rag.retrieval_failures", "strategy", "vector")
                        .increment();
            }
        }
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

    public Page<KnowledgeDocument> list(Pageable pageable) {
        return documents.findAll(pageable);
    }

    @Transactional
    public void delete(Long id) {
        KnowledgeDocument document =
                documents.findById(id).orElseThrow(() -> ApiException.notFound("文档不存在"));
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
        }
        documents.delete(document);
    }

    private void validate(MultipartFile file) {
        if (file.isEmpty()) throw ApiException.badRequest("文件不能为空");
        String type = Optional.ofNullable(file.getContentType()).orElse("application/octet-stream");
        if (!SUPPORTED_TYPES.contains(type)) {
            throw ApiException.badRequest("仅支持 Markdown、TXT、PDF 和 Word 文件");
        }
    }

    private String clean(String value) {
        return value.replace("\u0000", "")
                .replaceAll("[\\t\\x0B\\f\\r]+", " ")
                .replaceAll(" *\\n *", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }

    private List<String> split(String text, int size, int overlap) {
        List<String> result = new ArrayList<>();
        for (int start = 0; start < text.length(); start += size - overlap) {
            int end = Math.min(text.length(), start + size);
            result.add(text.substring(start, end));
            if (end == text.length()) break;
        }
        return result;
    }

    private String section(String text, int index) {
        return text.lines()
                .filter(line -> line.startsWith("#"))
                .findFirst()
                .map(line -> line.replaceFirst("^#+\\s*", ""))
                .orElse("第 " + (index + 1) + " 节");
    }

    private String keywords(String text) {
        return terms(text).stream()
                .limit(10)
                .reduce((left, right) -> left + "," + right)
                .orElse("");
    }

    private List<String> terms(String text) {
        return Pattern.compile("[^\\p{L}\\p{N}]+")
                .splitAsStream(text.toLowerCase())
                .filter(value -> value.length() >= 2)
                .distinct()
                .limit(20)
                .toList();
    }

    private String shorten(String value, int length) {
        if (value == null) return null;
        return value.substring(0, Math.min(value.length(), length));
    }

    public record Source(String documentName, String section, String content, int chunkIndex) {}
}
