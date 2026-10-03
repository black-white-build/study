package com.heartpilot.module.conversation.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.heartpilot.common.exception.ApiException;
import com.heartpilot.infrastructure.ai.AnswerPromptBuilder;
import com.heartpilot.infrastructure.ai.AnswerSafetyPolicy;
import com.heartpilot.infrastructure.ai.CitationValidator;
import com.heartpilot.infrastructure.ai.ConversationClassifier;
import com.heartpilot.infrastructure.ai.ConversationClassifier.Route;
import com.heartpilot.infrastructure.ai.PromptRegistry;
import com.heartpilot.infrastructure.ai.RelationshipAiClient;
import com.heartpilot.infrastructure.ai.StructuredAnswerRenderer;
import com.heartpilot.module.agent.service.RedisResultCacheService;
import com.heartpilot.module.conversation.entity.AiConversation;
import com.heartpilot.module.conversation.entity.AiMessage;
import com.heartpilot.module.conversation.entity.enums.AiMessageStatus;
import com.heartpilot.module.conversation.repository.ConversationRepository;
import com.heartpilot.module.conversation.repository.MessageRepository;
import com.heartpilot.module.conversation.service.ConversationContextService;
import com.heartpilot.module.knowledge.entity.enums.KnowledgeEvidenceLevel;
import com.heartpilot.module.knowledge.service.KnowledgeService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

/**
 * 对话服务核心链路测试：覆盖流式生成完成落库、检索命中状态审计、安全直答旁路、
 * 缓存命中旁路、同会话并发拦截与手动停止六条关键路径。
 */
class ConversationServiceImplTest {
    private final ObjectMapper json = new ObjectMapper();

    private ConversationRepository conversations;
    private MessageRepository messages;
    private RelationshipAiClient ai;
    private KnowledgeService knowledge;
    private AnswerPromptBuilder promptBuilder;
    private AnswerSafetyPolicy safetyPolicy;
    private ConversationClassifier classifier;
    private CitationValidator citationValidator;
    private PromptRegistry promptRegistry;
    private ConversationContextService contextService;
    private RedisResultCacheService cache;

    /** 最近一次保存的 assistant 消息，用于断言最终落库内容 */
    private final List<AiMessage> savedAssistants = new ArrayList<>();

    /** 模拟数据库自增主键 */
    private final java.util.concurrent.atomic.AtomicLong messageIds =
            new java.util.concurrent.atomic.AtomicLong(100);

    private ConversationServiceImpl service;

    @BeforeEach
    void setUp() {
        conversations = mock(ConversationRepository.class);
        messages = mock(MessageRepository.class);
        ai = mock(RelationshipAiClient.class);
        knowledge = mock(KnowledgeService.class);
        promptBuilder = mock(AnswerPromptBuilder.class);
        safetyPolicy = mock(AnswerSafetyPolicy.class);
        classifier = mock(ConversationClassifier.class);
        citationValidator = mock(CitationValidator.class);
        promptRegistry = mock(PromptRegistry.class);
        contextService = mock(ConversationContextService.class);
        cache = mock(RedisResultCacheService.class);
        savedAssistants.clear();

        AiConversation conversation = new AiConversation();
        conversation.setId(1L);
        conversation.setUserId(2L);
        conversation.setModel("qwen-plus");
        when(conversations.findByIdAndUserId(1L, 2L)).thenReturn(Optional.of(conversation));
        when(conversations.save(any(AiConversation.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        // 消息仓库：保存时返回同一实体并记录 assistant 消息，便于断言最终状态
        when(messages.save(any(AiMessage.class)))
                .thenAnswer(
                        invocation -> {
                            AiMessage message = invocation.getArgument(0);
                            // 模拟数据库分配自增 ID，避免 Map.of("messageId", ...) 因 null 抛 NPE
                            if (message.getId() == null) message.setId(messageIds.incrementAndGet());
                            if ("ASSISTANT".equals(message.getRole())) savedAssistants.add(message);
                            return message;
                        });
        // 首轮对话无历史消息，空列表即可
        when(messages.findByConversationIdAndUserIdOrderByCreatedAtAsc(1L, 2L))
                .thenReturn(List.of());

        when(promptBuilder.build(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn("测试提示词");
        when(knowledge.currentIndexVersion()).thenReturn("test-v1");
        when(promptRegistry.versions())
                .thenReturn(
                        Map.of(
                                "answer", "1.1.0",
                                "safety", "1.1.0",
                                "classifier", "1.0.0",
                                "citation", "1.0.0"));
        when(contextService.update(any(), any(), any(), anyString()))
                .thenReturn(
                        new ConversationContextService.Snapshot(
                                List.of(), List.of(), List.of(), List.of()));
        when(cache.getModelResult(anyString())).thenReturn(Optional.empty());

        service =
                new ConversationServiceImpl(
                        conversations,
                        messages,
                        ai,
                        knowledge,
                        promptBuilder,
                        safetyPolicy,
                        classifier,
                        new StructuredAnswerRenderer(),
                        citationValidator,
                        promptRegistry,
                        contextService,
                        json,
                        new SimpleMeterRegistry(),
                        cache,
                        20,
                        16_000,
                        2,
                        0.8,
                        2.0);
    }

    /** 一条可复用的检索来源，证据等级 HIGH，用于命中场景 */
    private KnowledgeService.Source source() {
        return new KnowledgeService.Source(
                1L,
                "非暴力沟通手册",
                "四要素",
                "非暴力沟通以观察、感受、需要、请求四步为基础。",
                0,
                "沟通基础",
                "通用",
                "通用",
                "心旅知识库",
                null,
                "1.0.0",
                null,
                KnowledgeEvidenceLevel.HIGH,
                0.91,
                "test-v1");
    }

    /** 放行式安全决策：CONTINUE + REAL 语境 */
    private void continueSafety() {
        when(safetyPolicy.evaluate(anyString()))
                .thenReturn(AnswerSafetyPolicy.Decision.continueWithModel(AnswerSafetyPolicy.Context.REAL));
    }

    /** 知识问答路由：需要检索知识库 */
    private void knowledgeRoute() {
        when(classifier.classify(anyString()))
                .thenReturn(
                        new ConversationClassifier.Classification(
                                Route.KNOWLEDGE_QA,
                                "沟通基础",
                                true,
                                false,
                                0.9,
                                List.of("KNOWLEDGE_QUESTION")));
    }

    /** 正常流式：逐段推送后完成，落库修正后的完整文本，检索命中写入审计。 */
    @Test
    void streamingFlowCompletesAndRecordsRetrievalHit() throws Exception {
        continueSafety();
        knowledgeRoute();
        when(knowledge.retrieve(anyString(), anyInt())).thenReturn(List.of(source()));
        when(ai.stream(anyString())).thenReturn(Flux.just("观察", "感受", "需要"));
        when(citationValidator.validate(anyString(), anyList()))
                .thenReturn(
                        new CitationValidator.Result(
                                "观察感受需要",
                                List.of(),
                                0,
                                0,
                                0,
                                List.of(),
                                CitationValidator.Status.PASSED));

        service.send(1L, 2L, "非暴力沟通是什么？", null);

        AiMessage assistant = savedAssistants.get(savedAssistants.size() - 1);
        assertEquals("观察感受需要", assistant.getContent());
        assertEquals(AiMessageStatus.COMPLETED, assistant.getStatus());
        assertEquals("PASSED", assistant.getCitationStatus());
        JsonNode audit = json.readTree(assistant.getAuditJson());
        assertEquals("HIT", audit.get("retrievalStatus").asText());
        assertEquals(1, audit.get("retrievedCount").asInt());
        verify(ai).stream(anyString());
        verify(cache).putModelResult(anyString(), eq("观察感受需要"));
    }

    /** 检索未命中：审计标记 MISS 且条数为 0，流程仍正常完成。 */
    @Test
    void retrievalMissMarksAuditAndStillCompletes() throws Exception {
        continueSafety();
        knowledgeRoute();
        when(knowledge.retrieve(anyString(), anyInt())).thenReturn(List.of());
        when(ai.stream(anyString())).thenReturn(Flux.just("模型自己的回答"));
        when(citationValidator.validate(anyString(), anyList()))
                .thenReturn(
                        new CitationValidator.Result(
                                "模型自己的回答",
                                List.of(),
                                0,
                                0,
                                0,
                                List.of(),
                                CitationValidator.Status.PASSED));

        service.send(1L, 2L, "伴侣不理我怎么办？", null);

        AiMessage assistant = savedAssistants.get(savedAssistants.size() - 1);
        assertEquals(AiMessageStatus.COMPLETED, assistant.getStatus());
        JsonNode audit = json.readTree(assistant.getAuditJson());
        assertEquals("MISS", audit.get("retrievalStatus").asText());
        assertEquals(0, audit.get("retrievedCount").asInt());
    }

    /** 安全直答：不走模型也不检索，直接返回固定话术，审计标记 SKIPPED。 */
    @Test
    void safetyDirectResponseSkipsModelAndRetrieval() throws Exception {
        when(safetyPolicy.evaluate(anyString()))
                .thenReturn(
                        AnswerSafetyPolicy.Decision.safety(
                                "我听到你提到的情况可能涉及人身安全。",
                                "REAL_WORLD_DANGER",
                                AnswerSafetyPolicy.Context.REAL));
        when(classifier.classify(anyString()))
                .thenReturn(
                        new ConversationClassifier.Classification(
                                Route.SAFETY, "安全", false, false, 0.99, List.of("SAFETY")));

        service.send(1L, 2L, "我要自杀", null);

        AiMessage assistant = savedAssistants.get(savedAssistants.size() - 1);
        assertEquals("我听到你提到的情况可能涉及人身安全。", assistant.getContent());
        assertEquals(AiMessageStatus.COMPLETED, assistant.getStatus());
        JsonNode audit = json.readTree(assistant.getAuditJson());
        assertEquals("SKIPPED", audit.get("retrievalStatus").asText());
        assertEquals(0, audit.get("retrievedCount").asInt());
        verify(ai, never()).stream(anyString());
        verify(knowledge, never()).retrieve(anyString(), anyInt());
    }

    /** 缓存命中：直接复用缓存结果，不调用模型，命中标记落库。 */
    @Test
    void cacheHitReusesCachedResultWithoutModelCall() throws Exception {
        continueSafety();
        knowledgeRoute();
        when(knowledge.retrieve(anyString(), anyInt())).thenReturn(List.of(source()));
        when(cache.getModelResult(anyString())).thenReturn(Optional.of("缓存的完整回答"));
        when(citationValidator.validate(anyString(), anyList()))
                .thenReturn(
                        new CitationValidator.Result(
                                "缓存的完整回答",
                                List.of(),
                                0,
                                0,
                                0,
                                List.of(),
                                CitationValidator.Status.PASSED));

        service.send(1L, 2L, "如何提出请求？", null);

        AiMessage assistant = savedAssistants.get(savedAssistants.size() - 1);
        assertEquals("缓存的完整回答", assistant.getContent());
        assertEquals(AiMessageStatus.COMPLETED, assistant.getStatus());
        assertTrue(assistant.isCacheHit());
        verify(ai, never()).stream(anyString());
    }

    /** 同会话并发生成：第一个任务未结束时第二次发送抛 409。 */
    @Test
    void concurrentSendOnSameConversationIsRejected() {
        continueSafety();
        knowledgeRoute();
        when(knowledge.retrieve(anyString(), anyInt())).thenReturn(List.of(source()));
        // 永不完成的流让第一个生成任务一直占住会话锁
        when(ai.stream(anyString())).thenReturn(Flux.never());

        service.send(1L, 2L, "第一条消息", null);

        ApiException exception =
                assertThrows(
                        ApiException.class,
                        () -> service.send(1L, 2L, "第二条消息", null));
        assertEquals("GENERATION_ACTIVE", exception.code());
    }

    /** 手动停止：中断挂起任务并把消息标记为 CANCELLED。 */
    @Test
    void stopCancelsActiveGeneration() {
        continueSafety();
        knowledgeRoute();
        when(knowledge.retrieve(anyString(), anyInt())).thenReturn(List.of(source()));
        when(ai.stream(anyString())).thenReturn(Flux.never());

        service.send(1L, 2L, "正在生成的消息", null);
        boolean stopped = service.stop(1L, 2L);

        assertTrue(stopped);
        AiMessage assistant = savedAssistants.get(savedAssistants.size() - 1);
        assertEquals(AiMessageStatus.CANCELLED, assistant.getStatus());
    }
}
