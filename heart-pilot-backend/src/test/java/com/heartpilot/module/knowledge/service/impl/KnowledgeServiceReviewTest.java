package com.heartpilot.module.knowledge.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.heartpilot.common.exception.ApiException;
import com.heartpilot.module.agent.service.RedisResultCacheService;
import com.heartpilot.module.file.service.StorageService;
import com.heartpilot.module.knowledge.entity.KnowledgeChunk;
import com.heartpilot.module.knowledge.entity.KnowledgeDocument;
import com.heartpilot.module.knowledge.entity.enums.KnowledgeDocumentStatus;
import com.heartpilot.module.knowledge.entity.enums.KnowledgeReviewStatus;
import com.heartpilot.module.knowledge.repository.KnowledgeChunkRepository;
import com.heartpilot.module.knowledge.repository.KnowledgeDocumentRepository;
import com.heartpilot.module.knowledge.service.KnowledgeMarkdownParser;
import com.heartpilot.module.knowledge.service.KnowledgeQueryPlanner;
import com.heartpilot.module.knowledge.service.KnowledgeService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.mock.web.MockMultipartFile;

/** 知识库管理端审核闭环测试：覆盖切片内容读取、审核状态流转及非法状态保护。 */
class KnowledgeServiceReviewTest {
    private KnowledgeDocumentRepository documents;
    private KnowledgeChunkRepository chunks;
    private StorageService storage;
    private VectorStore vectors;
    private KnowledgeServiceImpl service;

    @BeforeEach
    void setUp() {
        documents = mock(KnowledgeDocumentRepository.class);
        chunks = mock(KnowledgeChunkRepository.class);
        storage = mock(StorageService.class);
        vectors = mock(VectorStore.class);
        service =
                new KnowledgeServiceImpl(
                        documents,
                        chunks,
                        storage,
                        vectors,
                        new SimpleMeterRegistry(),
                        mock(RedisResultCacheService.class),
                        mock(KnowledgeQueryPlanner.class),
                        mock(KnowledgeMarkdownParser.class),
                        "not-configured",
                        0.58);
    }

    @Test
    void uploadShouldDefaultToInReviewAndFinishAsReady() throws Exception {
        MockMultipartFile file =
                new MockMultipartFile(
                        "file", "test.txt", "text/plain", "用于测试的知识正文".getBytes());
        KnowledgeService.DocumentMetadata metadata =
                new KnowledgeService.DocumentMetadata(
                        "沟通基础", "通用沟通", "通用", "测试来源", "", "1.0", null, "", null);
        when(storage.store(file.getBytes(), "test.txt", "text/plain", "knowledge"))
                .thenReturn(
                        new StorageService.StoredObject(
                                "knowledge/test.txt", file.getSize(), "text/plain", "test.txt"));
        when(documents.saveAndFlush(org.mockito.ArgumentMatchers.any(KnowledgeDocument.class)))
                .thenAnswer(
                        invocation -> {
                            KnowledgeDocument saved = invocation.getArgument(0);
                            saved.setId(10L);
                            return saved;
                        });
        when(documents.save(org.mockito.ArgumentMatchers.any(KnowledgeDocument.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        KnowledgeDocument result = service.upload(file, metadata, 333L);

        assertThat(result.getStatus()).isEqualTo(KnowledgeDocumentStatus.READY);
        assertThat(result.getReviewStatus()).isEqualTo(KnowledgeReviewStatus.IN_REVIEW);
        assertThat(result.getChunkCount()).isEqualTo(1);
        verify(vectors).add(org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    void contentShouldJoinPersistedChunksInRepositoryOrder() {
        KnowledgeDocument document = document(7L, KnowledgeDocumentStatus.READY);
        KnowledgeChunk first = chunk(7L, 0, "第一段内容");
        KnowledgeChunk second = chunk(7L, 1, "第二段内容");
        when(documents.findById(7L)).thenReturn(Optional.of(document));
        when(chunks.findByDocumentIdOrderByChunkIndexAsc(7L))
                .thenReturn(List.of(first, second));

        KnowledgeService.DocumentContent result = service.content(7L);

        assertThat(result.documentId()).isEqualTo(7L);
        assertThat(result.originalName()).isEqualTo("test.md");
        assertThat(result.chunkCount()).isEqualTo(2);
        assertThat(result.content()).isEqualTo("第一段内容\n\n第二段内容");
    }

    @Test
    void approveShouldMoveReadyDocumentIntoRetrievalPoolAndRefreshIndexVersion() {
        KnowledgeDocument document = document(8L, KnowledgeDocumentStatus.READY);
        document.setReviewStatus(KnowledgeReviewStatus.IN_REVIEW);
        document.setIndexVersion("manual-1.0");
        when(documents.findById(8L)).thenReturn(Optional.of(document));
        when(documents.save(document)).thenReturn(document);

        KnowledgeDocument result = service.approve(8L);

        assertThat(result.getReviewStatus()).isEqualTo(KnowledgeReviewStatus.APPROVED);
        assertThat(result.getIndexVersion()).startsWith("approved-");
        verify(documents).save(document);
    }

    @Test
    void approveShouldRejectDocumentThatIsNotReady() {
        KnowledgeDocument document = document(9L, KnowledgeDocumentStatus.PROCESSING);
        document.setReviewStatus(KnowledgeReviewStatus.IN_REVIEW);
        when(documents.findById(9L)).thenReturn(Optional.of(document));

        assertThatThrownBy(() -> service.approve(9L))
                .isInstanceOf(ApiException.class)
                .hasMessage("文档尚未处理完成，不能通过审核");
    }

    /** 构造审核测试使用的最小文档实体。 */
    private KnowledgeDocument document(Long id, KnowledgeDocumentStatus status) {
        KnowledgeDocument document = new KnowledgeDocument();
        document.setId(id);
        document.setOriginalName("test.md");
        document.setStatus(status);
        return document;
    }

    /** 构造内容拼接测试使用的切片实体。 */
    private KnowledgeChunk chunk(Long documentId, int index, String content) {
        KnowledgeChunk chunk = new KnowledgeChunk();
        chunk.setDocumentId(documentId);
        chunk.setChunkIndex(index);
        chunk.setContent(content);
        return chunk;
    }
}
