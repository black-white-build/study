package com.heartpilot.module.conversation.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.heartpilot.common.exception.ApiException;
import com.heartpilot.infrastructure.ai.AnswerPromptBuilder;
import com.heartpilot.infrastructure.ai.AnswerSafetyPolicy;
import com.heartpilot.infrastructure.ai.CitationValidator;
import com.heartpilot.infrastructure.ai.ConversationClassifier;
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
import com.heartpilot.module.conversation.service.ConversationService;
import com.heartpilot.module.knowledge.service.KnowledgeService;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

/**
 * 对话服务实现。 负责会话与消息的完整管理，核心是基于 SSE 的流式 AI 问答： 用户发消息 → 写入用户消息 → 安全分流 → 检索知识库来源 → 构建带历史上下文的 Prompt →
 * 调用大模型流式接口 → 汇总并完成结构化/引用校验 → 通过 SSE 推送给前端 → 落库消息并统计 token/成本。
 *
 * <p>可靠性与性能设计要点： - 同一会话同一时刻只允许一个生成任务（active Map 锁），重复发送返回 409 - Redis 结果缓存：相同用户+模型+Prompt
 * 直接命中缓存，避免重复调用模型 - Reactor 流式订阅带指数退避重试（maxRetries），首字延迟与总耗时接入 Micrometer 监控 - SSE 超时/出错自动触发
 * stop，中断订阅并把消息标记为 CANCELLED - 成本以"微元"为整数单位统计输入/输出/缓存节省，避免浮点误差
 */
@Service
public class ConversationServiceImpl implements ConversationService {
    private final ConversationRepository conversations;
    private final MessageRepository messages;

    /** 大模型流式客户端 */
    private final RelationshipAiClient ai;

    /** 知识库检索服务，发送消息时先检索相关知识片段注入 Prompt */
    private final KnowledgeService knowledge;

    /** 按路由类型组装最终 Prompt 区段（系统设定/检索/历史/当前输入彼此隔离） */
    private final AnswerPromptBuilder promptBuilder;
    /** 安全策略：评估用户输入，决定是否直接应答、拒绝或放行到模型 */
    private final AnswerSafetyPolicy safetyPolicy;
    /** 对话分类器：判断话题、路由、是否需要检索知识库及置信度 */
    private final ConversationClassifier classifier;
    /** 结构化渲染器：把模型输出规范为带引用编号的标准格式 */
    private final StructuredAnswerRenderer answerRenderer;
    /** 引用校验器：核对结构化回答中的引用编号是否与检索来源一致 */
    private final CitationValidator citationValidator;
    /** Prompt 版本注册表，记录所用模板版本用于消息审计 */
    private final PromptRegistry promptRegistry;
    /** 多轮上下文服务：维护并更新会话级上下文快照 */
    private final ConversationContextService contextService;
    private final ObjectMapper json;
    private final MeterRegistry metrics;

    /** 携带到模型的最大历史消息条数，配置项 app.chat.max-context-messages */
    private final int maxMessages;

    /** Prompt 累计最大字符数，超出后截断更早的历史，防止超长上下文 */
    private final int maxChars;

    /** 模型流失败后的最大自动重试次数 */
    private final int maxRetries;

    /** Redis 模型结果缓存，命中则跳过模型调用直接返回 */
    private final RedisResultCacheService cache;

    /** 输入模型单价（元/百万 token），用于成本估算 */
    private final double inputCnyPerMillionTokens;

    /** 输出模型单价（元/百万 token） */
    private final double outputCnyPerMillionTokens;

    /** 当前正在进行的生成任务（key=userId:conversationId），用于并发控制与停止 */
    private final Map<String, Generation> active = new ConcurrentHashMap<>();

    /** 构造器注入依赖与各项配置项。 同时注册 active_generations 仪表盘指标，实时观察在途生成任务数。 */
    public ConversationServiceImpl(
            ConversationRepository conversations,
            MessageRepository messages,
            RelationshipAiClient ai,
            KnowledgeService knowledge,
            AnswerPromptBuilder promptBuilder,
            AnswerSafetyPolicy safetyPolicy,
            ConversationClassifier classifier,
            StructuredAnswerRenderer answerRenderer,
            CitationValidator citationValidator,
            PromptRegistry promptRegistry,
            ConversationContextService contextService,
            ObjectMapper json,
            MeterRegistry metrics,
            RedisResultCacheService cache,
            @Value("${app.chat.max-context-messages:20}") int maxMessages,
            @Value("${app.chat.max-context-characters:16000}") int maxChars,
            @Value("${app.chat.max-retries:2}") int maxRetries,
            @Value("${app.chat.input-cny-per-million-tokens:0.8}") double inputCnyPerMillionTokens,
            @Value("${app.chat.output-cny-per-million-tokens:2.0}")
                    double outputCnyPerMillionTokens) {
        this.conversations = conversations;
        this.messages = messages;
        this.ai = ai;
        this.knowledge = knowledge;
        this.promptBuilder = promptBuilder;
        this.safetyPolicy = safetyPolicy;
        this.classifier = classifier;
        this.answerRenderer = answerRenderer;
        this.citationValidator = citationValidator;
        this.promptRegistry = promptRegistry;
        this.contextService = contextService;
        this.json = json;
        this.metrics = metrics;
        this.cache = cache;
        this.maxMessages = maxMessages;
        this.maxChars = maxChars;
        this.maxRetries = maxRetries;
        this.inputCnyPerMillionTokens = inputCnyPerMillionTokens;
        this.outputCnyPerMillionTokens = outputCnyPerMillionTokens;
        Gauge.builder("heartpilot.chat.active_generations", active, Map::size).register(metrics);
    }

    /**
     * 实现：按用户维度过滤掉已归档会话后分页返回，排序与每页大小由调用方通过 Pageable 传入，本层不再附加默认排序。
     */
    @Override
    public Page<AiConversation> list(Long userId, Pageable pageable) {
        // 分页返回当前用户未归档的会话，按最近消息时间排序由调用方通过 Pageable 指定
        return conversations.findByUserIdAndArchivedFalse(userId, pageable);
    }

    /** 创建会话，标题为空时默认"新的倾诉" */
    @Override
    public AiConversation create(Long userId, String title) {
        AiConversation conversation = new AiConversation();
        conversation.setUserId(userId);
        conversation.setTitle(title == null || title.isBlank() ? "新的倾诉" : title.trim());
        conversation.setLastMessageAt(Instant.now());
        return conversations.save(conversation);
    }

    /** 按 ID + 用户取会话，不存在抛 404，所有鉴权操作都经过这里 */
    @Override
    public AiConversation get(Long id, Long userId) {
        return conversations
                .findByIdAndUserId(id, userId)
                .orElseThrow(() -> ApiException.notFound("会话不存在"));
    }

    /** 取全量历史消息，先校验会话归属 */
    @Override
    public List<AiMessage> history(Long id, Long userId) {
        get(id, userId);
        return messages.findByConversationIdAndUserIdOrderByCreatedAtAsc(id, userId);
    }

    /** 分页取历史消息 */
    @Override
    public Page<AiMessage> history(Long id, Long userId, Pageable pageable) {
        get(id, userId);
        return messages.findByConversationIdAndUserId(id, userId, pageable);
    }

    /** 重命名会话标题 */
    @Transactional
    @Override
    public AiConversation rename(Long id, Long userId, String title) {
        AiConversation conversation = get(id, userId);
        conversation.setTitle(title.strip());
        return conversation;
    }

    /** 删除会话：先停止正在进行的生成，再删消息与会话本身 */
    @Transactional
    @Override
    public void delete(Long id, Long userId) {
        AiConversation conversation = get(id, userId);
        stop(id, userId);
        messages.deleteByConversationIdAndUserId(id, userId);
        contextService.delete(id, userId);
        conversations.delete(conversation);
    }

    /**
     * 发送用户消息并 SSE 流式生成回复。 流程：校验会话 → 同会话并发拦截 → 落库用户消息 → 知识库检索 → 落库 assistant 占位消息(STREAMING) → 构建
     * Prompt → 查缓存（命中直接推送缓存结果）→ 订阅模型流逐字推送 → 成功/失败/取消分别收尾。
     */
    @Override
    public SseEmitter send(Long conversationId, Long userId, String content, Long regeneratedFrom) {
        AiConversation conversation = get(conversationId, userId);
        String normalizedContent = content.strip();
        String key = key(conversationId, userId);
        // 同一会话已有生成在跑，拒绝并发，避免两条流互相覆盖
        if (active.containsKey(key)) {
            throw ApiException.conflict("GENERATION_ACTIVE", "当前会话正在生成");
        }

        // 先落库用户消息
        AiMessage userMessage = new AiMessage();
        userMessage.setConversationId(conversationId);
        userMessage.setUserId(userId);
        userMessage.setRole("USER");
        userMessage.setContent(normalizedContent);
        userMessage.setInputTokens(tokens(normalizedContent));
        userMessage.setModel(conversation.getModel());
        userMessage.setRegeneratedFromId(regeneratedFrom);
        messages.save(userMessage);

        // 更新多轮上下文
        ConversationContextService.Snapshot contextState =
                contextService.update(
                        conversationId, userId, userMessage.getId(), normalizedContent);

        // 首轮对话（仅这一条用户消息）时，用首条内容自动作为会话标题
        if (history(conversationId, userId).size() <= 1) {
            conversation.setTitle(
                    normalizedContent.substring(0, Math.min(24, normalizedContent.length())));
        }
        conversation.setLastMessageAt(Instant.now());
        conversations.save(conversation);

        // 安全评估 + 话题分类，并按安全结论决定最终路由（安全类/拒绝类/正常类）
        AnswerSafetyPolicy.Decision safetyDecision = safetyPolicy.evaluate(normalizedContent);

        ConversationClassifier.Classification classification =
                classifier.classify(normalizedContent);

        // 把"安全决策 + 分类结果"合并成最终路由
        ConversationClassifier.Route route =
                // 安全优先级高于分类。只要安全判断命中了高危（SAFETY/REFUSAL），无论分类器怎么判，都按安全结果走，防止高危内容被普通分类流程放行。
                switch (safetyDecision.kind()) {
                    // （真实即时危险，如自杀/暴力/威胁）：最终路由强制设为 SAFETY，走安全话术流程，不再让分类器决定。
                    case SAFETY -> ConversationClassifier.Route.SAFETY;
                    // （边界违规/心理诊断请求，如 PUA、纠缠、诊断人格）：最终路由强制设为 DISALLOWED，即禁止生成回答，直接返回固定拒绝话术。
                    case REFUSAL -> ConversationClassifier.Route.DISALLOWED;
                    // （安全放行）：才采用第 1 步分类器得出的 route，即真正进入 DECISION / CLARIFY / KNOWLEDGE_QA / SUPPORT 等业务路由。
                    case CONTINUE -> classification.route();
                };
        // 记录"最终路由"分布指标
        metrics.counter("heartpilot.chat.route", "route", route.name()).increment();
        // 记录"安全决策"细粒度指标
        metrics.counter(
                        "heartpilot.chat.safety_decision",
                        "kind",
                        safetyDecision.kind().name(),
                        "context",
                        safetyDecision.context().name())
                .increment();
        // 安全直接应答或分类判定无需知识时跳过检索，避免无谓的向量查询
        boolean retrievalSkipped =
                safetyDecision.respondsDirectly() || !classification.needsKnowledge();
        List<KnowledgeService.Source> sources =
                retrievalSkipped ? List.of() : knowledge.retrieve(normalizedContent, 4);
        // 记录检索命中状态：HIT=命中 / MISS=未命中 / SKIPPED=未检索（安全直答或无需知识）
        String retrievalStatus =
                retrievalSkipped ? "SKIPPED" : (sources.isEmpty() ? "MISS" : "HIT");
        metrics.counter("heartpilot.chat.retrieval", "status", retrievalStatus).increment();

        // 创建 assistant 占位消息，状态 STREAMING，内容随流式累积
        AiMessage assistant = new AiMessage();
        assistant.setConversationId(conversationId);
        assistant.setUserId(userId);
        assistant.setRole("ASSISTANT");
        assistant.setContent("");
        assistant.setStatus(AiMessageStatus.STREAMING);
        assistant.setModel(conversation.getModel());
        // 落库路由、安全等级、知识索引版本、Prompt 版本与上下文快照，便于事后审计与复现
        assistant.setRoute(route.name());
        assistant.setSafetyLevel(safetyDecision.kind().name());
        assistant.setKnowledgeIndexVersion(knowledge.currentIndexVersion());
        assistant.setPromptVersionsJson(writeJson(promptRegistry.versions(), "{}"));
        assistant.setConversationStateJson(writeJson(contextState, "{}"));
        // 汇总分类与安全原因写入审计 JSON
        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("route", route.name());
        audit.put("topic", classification.topic());
        audit.put("classifierConfidence", classification.confidence());
        audit.put("reasonCodes", classification.reasonCodes());
        audit.put("safetyReason", safetyDecision.reasonCode());
        audit.put("safetyContext", safetyDecision.context().name());
        audit.put("retrievalStatus", retrievalStatus);
        audit.put("retrievedCount", sources.size());
        assistant.setAuditJson(writeJson(audit, "{}"));
        try {
            // 把来源引用序列化为 JSON 存起来，前端可展示"参考了哪些文档"
            assistant.setSourcesJson(json.writeValueAsString(List.of()));
        } catch (Exception ignored) {
            // 序列化失败不阻断生成，来源降级为空数组
            assistant.setSourcesJson("[]");
        }
        assistant = messages.save(assistant);

        // 组装发给大模型的完整 Prompt
        String prompt =
                buildPrompt(
                        conversationId,
                        userId,
                        normalizedContent,
                        sources,
                        route,
                        stableContextJson(contextState));
        assistant.setInputTokens(tokens(prompt));
        // 预估算输入成本并记录，完成后再补输出成本
        assistant.setInputCostMicros(
                safetyDecision.respondsDirectly()
                        ? 0
                        : estimateCost(assistant.getInputTokens(), inputCnyPerMillionTokens));
        assistant.setEstimatedCostMicros(assistant.getInputCostMicros());
        messages.save(assistant);

        // 缓存键：安全结论 + 路由 + 知识索引版本 + Prompt 版本 + 用户+模型+Prompt，任一变化都不命中
        String modelCacheKey =
                "answer-v4|"
                        + safetyDecision.kind()
                        + "|"
                        + route
                        + "|"
                        + assistant.getKnowledgeIndexVersion()
                        + "|"
                        + assistant.getPromptVersionsJson()
                        + "|"
                        + userId
                        + "|"
                        + conversation.getModel()
                        + "|"
                        + prompt;
        // SSE 超时 180 秒，超时自动停止生成
        SseEmitter emitter = new SseEmitter(180_000L);
        Generation generation =
                new Generation(
                        emitter,
                        assistant,
                        modelCacheKey,
                        !safetyDecision.respondsDirectly(),
                        sources,
                        route,
                        retrievalStatus);
        active.put(key, generation);
        AiMessage savedAssistant = assistant;
        // 非计费任务（安全直接应答等）不查缓存，结果也不写回
        Optional<String> cachedResult =
                generation.billable ? cache.getModelResult(modelCacheKey) : Optional.empty();
        if (cachedResult.isPresent()) {
            // 缓存命中：一次性推送整段结果并直接收尾，不调用模型
            generation.assistant.setCacheHit(true);
            String cachedOutput = prepareOutput(generation, cachedResult.get());
            generation.text.append(cachedOutput);
            recordFirstToken(generation);
            event(
                    emitter,
                    "delta",
                    Map.of(
                            "content",
                            cachedOutput,
                            "messageId",
                            savedAssistant.getId(),
                            "cacheHit",
                            true));
            finishSuccess(key, generation);
            return emitter;
        }
        // 汇总模型流：逐段推送实现打字机效果，完成后统一做引用校验再收尾。
        // 两个数据源二选一：安全直接应答（整段）和正常生成（逐段）
        Flux<String> answerStream =
                safetyDecision.respondsDirectly()
                        ? Flux.just(safetyDecision.directResponse())
                        : ai.stream(prompt);
        generation.disposable =
                answerStream
                        // 重试策略：只允许在首字推送前重试；一旦已有内容发给前端，
                        // 中断不再自动重试，避免用户看到重复或错位的内容。
                        .retryWhen(
                                Retry.from(
                                        companion ->
                                                companion.flatMap(
                                                        retrySignal -> {
                                                            if (generation.firstToken.get()) {
                                                                // 已推过首字，放弃重试，直接上抛失败
                                                                return Mono.error(
                                                                        retrySignal.failure());
                                                            }
                                                            long attempt =
                                                                    retrySignal.totalRetries();
                                                            if (attempt >= maxRetries) {
                                                                // 未推送但已耗尽重试次数，上抛失败
                                                                return Mono.error(
                                                                        retrySignal.failure());
                                                            }
                                                            return Mono.delay(
                                                                    Duration.ofMillis(
                                                                            400L * (attempt + 1)));
                                                        })))
                        // 逐段订阅：每收到一个 token 片段就立即通过 SSE 推送，同时累积全文
                        .subscribe(
                                chunk -> {
                                    recordFirstToken(generation);
                                    generation.text.append(chunk);
                                    event(
                                            emitter,
                                            "delta",
                                            Map.of(
                                                    "content",
                                                    chunk,
                                                    "messageId",
                                                    savedAssistant.getId()));
                                },
                                error -> finishError(key, generation, error),
                                // 流正常结束：用累积全文做引用校验与回填，再把修正后的全文
                                // 随 done 事件一并下发，供前端覆盖流式期间的原始内容
                                () -> {
                                    String output =
                                            prepareOutput(
                                                    generation, generation.text.toString());
                                    generation.text.setLength(0);
                                    generation.text.append(output);
                                    finishSuccess(key, generation);
                                });
        // SSE 超时主动停止生成，中断订阅
        emitter.onTimeout(() -> stop(conversationId, userId));
        // 前端断连（关页面/断网）触发：同样主动 stop，释放并发锁和订阅资源
        emitter.onError(error -> stop(conversationId, userId));
        return emitter;
    }

    /** 重新生成：定位目标消息，向前找到最近一条 USER 消息，复用其内容走正常发送流程。 regeneratedFrom 记录被替换的原消息 ID，便于前端做替换关联。 */
    @Override
    public SseEmitter regenerate(Long conversationId, Long messageId, Long userId) {
        List<AiMessage> all = history(conversationId, userId);
        int index = -1;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).getId().equals(messageId)) {
                index = i;
                break;
            }
        }
        if (index < 0) throw ApiException.notFound("消息不存在");
        AiMessage target = all.get(index);
        // 目标本身是用户消息就用它，否则向前找最近一条用户消息
        AiMessage userMessage = "USER".equals(target.getRole()) ? target : null;
        for (int i = index - 1; userMessage == null && i >= 0; i--) {
            if ("USER".equals(all.get(i).getRole())) userMessage = all.get(i);
        }
        if (userMessage == null) throw ApiException.badRequest("找不到可重新生成的用户消息");
        return send(conversationId, userId, userMessage.getContent(), messageId);
    }

    /** 停止当前会话的生成：从 active 表移除并取消模型订阅，把 assistant 消息标记为 CANCELLED。 返回是否确实停止到了一个进行中的任务。 */
    @Override
    public boolean stop(Long conversationId, Long userId) {
        Generation generation = active.remove(key(conversationId, userId));
        if (generation == null) return false;
        // 取消 Reactor 订阅，中断流式读取
        if (generation.disposable != null) generation.disposable.dispose();
        persist(generation, AiMessageStatus.CANCELLED);
        event(
                generation.emitter,
                "done",
                Map.of(
                        "status",
                        AiMessageStatus.CANCELLED,
                        "messageId",
                        generation.assistant.getId()));
        generation.emitter.complete();
        recordDuration(generation, "cancelled");
        return true;
    }

    /**
     * 流式成功收尾：落库最终消息，推送 done 事件，并把结果写入 Redis 缓存供下次命中。 用 active.remove(key, generation) 做
     * CAS，防止重复停止/完成导致重复收尾。
     */
    private void finishSuccess(String key, Generation generation) {
        if (!active.remove(key, generation)) return;
        persist(generation, AiMessageStatus.COMPLETED);
        // done 事件：携带修正后的全文、来源引用 JSON 与引用校验状态，前端据此覆盖流式期间的原始内容
        Map<String, Object> donePayload = new LinkedHashMap<>();
        donePayload.put("status", AiMessageStatus.COMPLETED);
        donePayload.put("messageId", generation.assistant.getId());
        donePayload.put("outputTokens", generation.assistant.getOutputTokens());
        donePayload.put("cacheHit", generation.assistant.isCacheHit());
        donePayload.put("estimatedCostMicros", generation.assistant.getEstimatedCostMicros());
        donePayload.put("retrievalStatus", generation.retrievalStatus);
        donePayload.put("retrievedCount", generation.sources.size());
        donePayload.put("content", generation.text.toString());
        donePayload.put(
                "sourcesJson",
                generation.assistant.getSourcesJson() == null
                        ? "[]"
                        : generation.assistant.getSourcesJson());
        donePayload.put(
                "citationStatus",
                generation.assistant.getCitationStatus() == null
                        ? "NOT_APPLICABLE"
                        : generation.assistant.getCitationStatus());
        event(generation.emitter, "done", donePayload);
        // 非缓存命中且有内容时才写缓存，避免把空结果或缓存内容再次写回
        if (generation.billable
                && !generation.assistant.isCacheHit()
                && "PASSED".equals(generation.assistant.getCitationStatus())
                && generation.text.length() > 0) {
            cache.putModelResult(generation.modelCacheKey, generation.text.toString());
        }
        generation.emitter.complete();
        recordDuration(generation, "success");
    }

    /** 流式失败收尾：保存已生成的部分内容并标记 FAILED，推送 error 事件，记录失败计数 */
    private void finishError(String key, Generation generation, Throwable error) {
        if (!active.remove(key, generation)) return;
        generation.assistant.setContent(generation.text.toString());
        generation.assistant.setStatus(AiMessageStatus.FAILED);
        String message = error.getMessage() == null ? "模型调用失败" : error.getMessage();
        // 错误信息截断到 480 字符，与字段长度匹配
        generation.assistant.setErrorMessage(message.substring(0, Math.min(480, message.length())));
        messages.save(generation.assistant);
        event(
                generation.emitter,
                "error",
                Map.of("message", "模型调用失败，已完成自动重试", "messageId", generation.assistant.getId()));
        generation.emitter.complete();
        metrics.counter("heartpilot.chat.failures").increment();
        recordDuration(generation, "failure");
    }

    /** 把流式累积的最终内容持久化到 assistant 消息，并补全 token、成本、耗时统计。 命中缓存时输入/输出成本清零，改为计入"缓存节省成本"。 */
    private void persist(Generation generation, AiMessageStatus status) {
        generation.assistant.setContent(generation.text.toString());
        generation.assistant.setOutputTokens(tokens(generation.text.toString()));
        long outputCost =
                generation.billable
                        ? estimateCost(
                                generation.assistant.getOutputTokens(), outputCnyPerMillionTokens)
                        : 0;
        if (generation.assistant.isCacheHit()) {
            // 命中缓存：不产生实际费用，把原本应付的金额记为缓存节省
            generation.assistant.setCacheSavedCostMicros(
                    generation.assistant.getInputCostMicros() + outputCost);
            generation.assistant.setInputCostMicros(0);
            generation.assistant.setOutputCostMicros(0);
            generation.assistant.setEstimatedCostMicros(0);
        } else {
            generation.assistant.setOutputCostMicros(outputCost);
            generation.assistant.setEstimatedCostMicros(
                    generation.assistant.getInputCostMicros() + outputCost);
        }
        // 从启动纳秒时间戳计算总耗时（毫秒）
        generation.assistant.setProviderLatencyMs(
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - generation.startedAt));
        generation.assistant.setStatus(status);
        messages.save(generation.assistant);
    }

    /** 估算成本（微元）：token 数 × 单价，1 元 = 1e6 微元，单位换算在公式中抵消 */
    private long estimateCost(int tokenCount, double cnyPerMillionTokens) {
        // 1 元等于一百万微元，百万 token 单价因子在公式中相互抵消。
        return Math.max(0, Math.round(tokenCount * cnyPerMillionTokens));
    }

    /** 组装发给大模型的完整 Prompt。 将已审核的检索片段、最近历史和当前输入放入彼此隔离的 Prompt 区段。 */
    private String buildPrompt(
            Long conversationId,
            Long userId,
            String currentInput,
            List<KnowledgeService.Source> sources,
            ConversationClassifier.Route route,
            String contextState) {
        List<AiMessage> all =
                messages.findByConversationIdAndUserIdOrderByCreatedAtAsc(conversationId, userId);
        // 只取最近 maxMessages 条，更早的历史丢弃以控制上下文长度
        int from = Math.max(0, all.size() - maxMessages);
        StringBuilder history = new StringBuilder();
        // 逐条拼接历史，超出 maxChars 则停止追加更早的消息
        List<AiMessage> selected = all.subList(from, all.size());
        // 定位刚发送的这条用户消息，拼历史时跳过它（作为 currentInput 单独传入）
        int currentMessageIndex = -1;
        for (int index = selected.size() - 1; index >= 0; index--) {
            AiMessage message = selected.get(index);
            if ("USER".equals(message.getRole()) && currentInput.equals(message.getContent())) {
                currentMessageIndex = index;
                break;
            }
        }
        for (int index = 0; index < selected.size(); index++) {
            AiMessage message = selected.get(index);
            if (index == currentMessageIndex
                    || message.getContent() == null
                    || message.getContent().isBlank()) continue;
            String line =
                    ("USER".equals(message.getRole()) ? "用户" : "顾问")
                            + "："
                            + message.getContent()
                            + "\n";
            if (history.length() + line.length() <= maxChars) history.append(line);
        }
        StringBuilder retrieval = new StringBuilder();
        if (!sources.isEmpty()) {
            // 把检索片段编号写入 Prompt，供模型据此标注引用编号
            for (int index = 0; index < sources.size(); index++) {
                KnowledgeService.Source source = sources.get(index);
                retrieval
                        .append("[来源 ")
                        .append(index + 1)
                        .append("｜《")
                        .append(source.documentName())
                        .append("》·")
                        .append(source.section())
                        .append("｜发布方：")
                        .append(source.sourceName() == null ? "未标注" : source.sourceName())
                        .append("｜证据等级：")
                        .append(source.evidenceLevel())
                        .append("]\n")
                        .append(source.content())
                        .append("\n");
            }
        }
        return promptBuilder.build(
                route.name(), retrieval.toString(), contextState, history.toString(), currentInput);
    }

    /** 把检索来源组装为前端引用条目，携带编号、文档、章节、证据等级与索引版本等字段。 */
    private Map<String, Object> sourceCitation(KnowledgeService.Source source, int number) {
        Map<String, Object> citation = new LinkedHashMap<>();
        citation.put("number", number);
        citation.put("documentId", source.documentId());
        citation.put("document", source.documentName());
        citation.put("section", source.section());
        citation.put("chunk", source.chunkIndex());
        citation.put("category", source.category());
        citation.put("sourceName", source.sourceName());
        citation.put("sourceUrl", source.sourceUrl());
        citation.put("contentVersion", source.contentVersion());
        citation.put("evidenceLevel", source.evidenceLevel());
        citation.put("relevance", source.relevance());
        citation.put("indexVersion", source.indexVersion());
        return citation;
    }

    /**
     * 输出后处理：非计费回答直接透传；计费回答先结构化渲染、做引用校验，
     * 回写校验状态与实际引用来源，并上报引用相关指标，返回可下发给前端的最终文本。
     */
    private String prepareOutput(Generation generation, String raw) {
        if (!generation.billable) {
            // 非计费（安全直接应答等）：无引用可校验，标记为不适用
            generation.assistant.setCitationStatus("NOT_APPLICABLE");
            generation.assistant.setCitationValidationJson("{}");
            return raw;
        }
        // 结构化渲染模型输出，再用检索来源校验引用编号
        String structured =
                answerRenderer.normalize(raw, generation.route, !generation.sources.isEmpty());
        CitationValidator.Result validation =
                citationValidator.validate(structured, generation.sources);
        generation.assistant.setCitationStatus(validation.status().name());
        generation.assistant.setCitationValidationJson(writeJson(validation, "{}"));
        // 按校验出的有效引用编号，回填实际引用的来源列表
        List<Map<String, Object>> citedSources = new java.util.ArrayList<>();
        for (Integer number : validation.usedSourceNumbers()) {
            citedSources.add(sourceCitation(generation.sources.get(number - 1), number));
        }
        generation.assistant.setSourcesJson(writeJson(citedSources, "[]"));
        metrics.counter("heartpilot.chat.citation_validation", "status", validation.status().name())
                .increment();
        metrics.counter("heartpilot.chat.citations", "kind", "total")
                .increment(validation.totalCitations());
        metrics.counter("heartpilot.chat.citations", "kind", "invalid")
                .increment(validation.issues().size());
        metrics.counter("heartpilot.chat.citation_coverage", "kind", "required_claims")
                .increment(validation.knowledgeClaimCount());
        metrics.counter("heartpilot.chat.citation_coverage", "kind", "supported_claims")
                .increment(validation.supportedCitedClaimCount());
        return validation.answer();
    }

    /** 安全地把对象序列化为 JSON 字符串，失败时返回兜底值，不阻断主流程。 */
    private String writeJson(Object value, String fallback) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    /**
     * 把会话记忆快照稳定化为只含 key/value 的 JSON，供 Prompt 构建与模型缓存键使用。
     * 原始快照每条记忆带 sourceMessageId 与 updatedAt，二者每次消息写入都不同；
     * 若原样拼入 Prompt，相同上下文的相同问题也会因字节差异永远无法命中 Redis 模型缓存。
     * 剥离后 Prompt 对"相同上下文 + 相同首条问题"可跨会话复用，缓存得以生效。
     * 数据库审计字段不受影响：assistant.conversationStateJson 仍写入完整快照。
     */
    private String stableContextJson(ConversationContextService.Snapshot snapshot) {
        Map<String, Object> stable = new LinkedHashMap<>();
        stable.put("facts", stripMeta(snapshot.facts()));
        stable.put("assumptions", stripMeta(snapshot.assumptions()));
        stable.put("preferences", stripMeta(snapshot.preferences()));
        stable.put("emotions", stripMeta(snapshot.emotions()));
        return writeJson(stable, "{}");
    }

    /** 只保留记忆条目的 key 与 value，丢弃每次写入都会变化的 sourceMessageId 与 updatedAt。 */
    private List<Map<String, Object>> stripMeta(List<Map<String, Object>> items) {
        List<Map<String, Object>> result = new java.util.ArrayList<>(items.size());
        for (Map<String, Object> item : items) {
            Map<String, Object> stableItem = new LinkedHashMap<>();
            if (item.get("key") != null) stableItem.put("key", item.get("key"));
            if (item.get("value") != null) stableItem.put("value", item.get("value"));
            result.add(stableItem);
        }
        return result;
    }

    /** 记录首字延迟（TTFT）指标，用 CAS 保证只计第一个 token */
    private void recordFirstToken(Generation generation) {
        if (generation.firstToken.compareAndSet(false, true)) {
            Timer.builder("heartpilot.chat.time_to_first_token")
                    .register(metrics)
                    .record(System.nanoTime() - generation.startedAt, TimeUnit.NANOSECONDS);
        }
    }

    /** 记录整个生成耗时，并按结果（success/failure/cancelled）打标签 */
    private void recordDuration(Generation generation, String outcome) {
        Timer.builder("heartpilot.chat.generation.duration")
                .tag("outcome", outcome)
                .register(metrics)
                .record(System.nanoTime() - generation.startedAt, TimeUnit.NANOSECONDS);
    }

    /** 粗略估算 token 数：按中文约 3.5 字符/token，至少 1 */
    private int tokens(String text) {
        return text == null ? 0 : Math.max(1, (int) Math.ceil(text.length() / 3.5));
    }

    /** 生成任务并发控制 key：userId:conversationId，保证同会话同用户唯一 */
    private String key(Long conversationId, Long userId) {
        return userId + ":" + conversationId;
    }

    /** 发送一个 SSE 事件，客户端断开或连接未就绪时静默忽略（最终状态由连接回调落库）。 */
    private void event(SseEmitter emitter, String name, Object data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data));
        } catch (Exception ignored) {
            // 客户端断开、连接未就绪或发送失败都静默忽略，不阻断生成主流程。
            // 连接未初始化时 SseEmitter.send 抛 IllegalStateException（非 IOException），
            // 因此这里捕获 Exception 而非仅 IOException。
        }
    }

    /** 一次流式生成任务的运行时上下文。 持有 SSE 发射器、累积文本、assistant 消息、缓存键、起始时间与 Reactor 订阅。 */
    private static final class Generation {
        private final SseEmitter emitter;
        /* 流式过程中逐段累积的回复文本 */
        private final StringBuilder text = new StringBuilder();
        /* 对话消息类 */
        private final AiMessage assistant;
        /* Redis 模型结果缓存键，成功后写回 */
        private final String modelCacheKey;
        /* 是否计费 */
        private final boolean billable;
        /* 本次检索到的知识来源，用于输出后的引用校验与回填 */
        private final List<KnowledgeService.Source> sources;
        /* 检索状态：HIT=命中 / MISS=未命中 / SKIPPED=未检索（安全直答或无需知识） */
        private final String retrievalStatus;
        /* 对话路由类型，决定 Prompt 模板与引用渲染策略 */
        private final ConversationClassifier.Route route;
        /* 任务开始的纳秒时间戳，用于 TTFT 与总耗时统计 */
        private final long startedAt = System.nanoTime();
        /* 是否已收到首字，保证 TTFT 只计一次 */
        private final AtomicBoolean firstToken = new AtomicBoolean();
        /* Reactor 订阅句柄，stop 时 dispose 以中断流；volatile 保证跨线程可见 */
        private volatile Disposable disposable;

        private Generation(
                SseEmitter emitter,
                AiMessage assistant,
                String modelCacheKey,
                boolean billable,
                List<KnowledgeService.Source> sources,
                ConversationClassifier.Route route,
                String retrievalStatus) {
            this.emitter = emitter;
            this.assistant = assistant;
            this.modelCacheKey = modelCacheKey;
            this.billable = billable;
            this.sources = sources;
            this.route = route;
            this.retrievalStatus = retrievalStatus;
        }
    }
}
