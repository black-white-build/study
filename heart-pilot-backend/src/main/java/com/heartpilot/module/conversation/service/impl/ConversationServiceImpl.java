package com.heartpilot.module.conversation.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.heartpilot.common.exception.ApiException;
import com.heartpilot.infrastructure.ai.RelationshipAiClient;
import com.heartpilot.module.agent.service.RedisResultCacheService;
import com.heartpilot.module.conversation.entity.AiConversation;
import com.heartpilot.module.conversation.entity.AiMessage;
import com.heartpilot.module.conversation.entity.enums.AiMessageStatus;
import com.heartpilot.module.conversation.repository.ConversationRepository;
import com.heartpilot.module.conversation.repository.MessageRepository;
import com.heartpilot.module.conversation.service.ConversationService;
import com.heartpilot.module.knowledge.service.KnowledgeService;
import com.heartpilot.module.user.repository.AppUserRepository;
import com.heartpilot.module.user.repository.ProfileRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
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
import reactor.util.retry.Retry;

/**
 * 对话服务实现。
 * 负责会话与消息的完整管理，核心是基于 SSE 的流式 AI 问答：
 * 用户发消息 → 写入用户消息 → 检索知识库来源 → 构建带历史上下文/用户画像的 Prompt →
 * 调用大模型流式接口 → 逐字通过 SSE 推送给前端 → 完成后落库消息并统计 token/成本。
 *
 * 可靠性与性能设计要点：
 * - 同一会话同一时刻只允许一个生成任务（active Map 锁），重复发送返回 409
 * - Redis 结果缓存：相同用户+模型+Prompt 直接命中缓存，避免重复调用模型
 * - Reactor 流式订阅带指数退避重试（maxRetries），首字延迟与总耗时接入 Micrometer 监控
 * - SSE 超时/出错自动触发 stop，中断订阅并把消息标记为 CANCELLED
 * - 成本以"微元"为整数单位统计输入/输出/缓存节省，避免浮点误差
 */
@Service
public class ConversationServiceImpl implements ConversationService {
    private final ConversationRepository conversations;
    private final MessageRepository messages;
    /** 用户长期关系档案仓库，用于个性化 Prompt */
    private final ProfileRepository profiles;
    private final AppUserRepository users;
    /** 大模型流式客户端 */
    private final RelationshipAiClient ai;
    /** 知识库检索服务，发送消息时先检索相关知识片段注入 Prompt */
    private final KnowledgeService knowledge;
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

    /**
     * 构造器注入依赖与各项配置项。
     * 同时注册 active_generations 仪表盘指标，实时观察在途生成任务数。
     */
    public ConversationServiceImpl(
            ConversationRepository conversations,
            MessageRepository messages,
            ProfileRepository profiles,
            AppUserRepository users,
            RelationshipAiClient ai,
            KnowledgeService knowledge,
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
        this.profiles = profiles;
        this.users = users;
        this.ai = ai;
        this.knowledge = knowledge;
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

    @Override
    public Page<AiConversation> list(Long userId, Pageable pageable) {
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
        conversations.delete(conversation);
    }

    /**
     * 发送用户消息并 SSE 流式生成回复。
     * 流程：校验会话 → 同会话并发拦截 → 落库用户消息 → 知识库检索 → 落库 assistant 占位消息(STREAMING) →
     * 构建 Prompt → 查缓存（命中直接推送缓存结果）→ 订阅模型流逐字推送 → 成功/失败/取消分别收尾。
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

        // 首轮对话（仅这一条用户消息）时，用首条内容自动作为会话标题
        if (history(conversationId, userId).size() <= 1) {
            conversation.setTitle(
                    normalizedContent.substring(0, Math.min(24, normalizedContent.length())));
        }
        conversation.setLastMessageAt(Instant.now());
        conversations.save(conversation);

        // RAG：先检索与问题相关的知识片段，供后续注入 Prompt 并记录来源
        List<KnowledgeService.Source> sources = knowledge.retrieve(normalizedContent, 4);
        // 创建 assistant 占位消息，状态 STREAMING，内容随流式累积
        AiMessage assistant = new AiMessage();
        assistant.setConversationId(conversationId);
        assistant.setUserId(userId);
        assistant.setRole("ASSISTANT");
        assistant.setContent("");
        assistant.setStatus(AiMessageStatus.STREAMING);
        assistant.setModel(conversation.getModel());
        try {
            // 把来源引用序列化为 JSON 存起来，前端可展示"参考了哪些文档"
            assistant.setSourcesJson(
                    json.writeValueAsString(
                            sources.stream()
                                    .map(
                                            source ->
                                                    Map.of(
                                                            "document",
                                                            source.documentName(),
                                                            "section",
                                                            source.section(),
                                                            "chunk",
                                                            source.chunkIndex()))
                                    .toList()));
        } catch (Exception ignored) {
            // 序列化失败不阻断生成，来源降级为空数组
            assistant.setSourcesJson("[]");
        }
        assistant = messages.save(assistant);

        // 构建完整 Prompt（系统设定 + 用户画像 + 历史上下文 + 知识片段）
        String prompt = buildPrompt(conversationId, userId, sources);
        assistant.setInputTokens(tokens(prompt));
        // 预估算输入成本并记录，完成后再补输出成本
        assistant.setInputCostMicros(
                estimateCost(assistant.getInputTokens(), inputCnyPerMillionTokens));
        assistant.setEstimatedCostMicros(assistant.getInputCostMicros());
        messages.save(assistant);

        // 缓存键：用户+模型+Prompt，三者相同则结果可复用
        String modelCacheKey = userId + "|" + conversation.getModel() + "|" + prompt;
        // SSE 超时 180 秒，超时自动停止生成
        SseEmitter emitter = new SseEmitter(180_000L);
        Generation generation = new Generation(emitter, assistant, modelCacheKey);
        active.put(key, generation);
        AiMessage savedAssistant = assistant;
        Optional<String> cachedResult = cache.getModelResult(modelCacheKey);
        if (cachedResult.isPresent()) {
            // 缓存命中：一次性推送整段结果并直接收尾，不调用模型
            generation.assistant.setCacheHit(true);
            generation.text.append(cachedResult.get());
            event(
                    emitter,
                    "delta",
                    Map.of(
                            "content",
                            cachedResult.get(),
                            "messageId",
                            savedAssistant.getId(),
                            "cacheHit",
                            true));
            finishSuccess(key, generation);
            return emitter;
        }
        // 订阅模型流式输出：每收到一个增量片段就追加文本并通过 SSE delta 事件推给前端
        generation.disposable =
                ai.stream(prompt)
                        // 网络/模型瞬断时指数退避重试，最多 maxRetries 次
                        .retryWhen(Retry.backoff(maxRetries, Duration.ofMillis(400)))
                        .subscribe(
                                delta -> {
                                    recordFirstToken(generation);
                                    generation.text.append(delta);
                                    event(
                                            emitter,
                                            "delta",
                                            Map.of(
                                                    "content",
                                                    delta,
                                                    "messageId",
                                                    savedAssistant.getId()));
                                },
                                error -> finishError(key, generation, error),
                                () -> finishSuccess(key, generation));
        // SSE 超时或连接异常时主动停止生成，中断订阅
        emitter.onTimeout(() -> stop(conversationId, userId));
        emitter.onError(error -> stop(conversationId, userId));
        return emitter;
    }

    /**
     * 重新生成：定位目标消息，向前找到最近一条 USER 消息，复用其内容走正常发送流程。
     * regeneratedFrom 记录被替换的原消息 ID，便于前端做替换关联。
     */
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

    /**
     * 停止当前会话的生成：从 active 表移除并取消模型订阅，把 assistant 消息标记为 CANCELLED。
     * 返回是否确实停止到了一个进行中的任务。
     */
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
     * 流式成功收尾：落库最终消息，推送 done 事件，并把结果写入 Redis 缓存供下次命中。
     * 用 active.remove(key, generation) 做 CAS，防止重复停止/完成导致重复收尾。
     */
    private void finishSuccess(String key, Generation generation) {
        if (!active.remove(key, generation)) return;
        persist(generation, AiMessageStatus.COMPLETED);
        event(
                generation.emitter,
                "done",
                Map.of(
                        "status",
                        AiMessageStatus.COMPLETED,
                        "messageId",
                        generation.assistant.getId(),
                        "outputTokens",
                        generation.assistant.getOutputTokens(),
                        "cacheHit",
                        generation.assistant.isCacheHit(),
                        "estimatedCostMicros",
                        generation.assistant.getEstimatedCostMicros()));
        // 非缓存命中且有内容时才写缓存，避免把空结果或缓存内容再次写回
        if (!generation.assistant.isCacheHit() && generation.text.length() > 0) {
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

    /**
     * 把流式累积的最终内容持久化到 assistant 消息，并补全 token、成本、耗时统计。
     * 命中缓存时输入/输出成本清零，改为计入"缓存节省成本"。
     */
    private void persist(Generation generation, AiMessageStatus status) {
        generation.assistant.setContent(generation.text.toString());
        generation.assistant.setOutputTokens(tokens(generation.text.toString()));
        long outputCost =
                estimateCost(generation.assistant.getOutputTokens(), outputCnyPerMillionTokens);
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
        // One CNY equals one million micros, so the per-million-token factors cancel out.
        return Math.max(0, Math.round(tokenCount * cnyPerMillionTokens));
    }

    /**
     * 组装发给大模型的完整 Prompt。
     * 顺序：系统角色设定 → 用户称呼/情绪 → 长期关系档案 → 最近 N 条历史对话（按字符上限截断）→ RAG 知识片段。
     */
    private String buildPrompt(
            Long conversationId, Long userId, List<KnowledgeService.Source> sources) {
        List<AiMessage> all =
                messages.findByConversationIdAndUserIdOrderByCreatedAtAsc(conversationId, userId);
        // 只取最近 maxMessages 条，更早的历史丢弃以控制上下文长度
        int from = Math.max(0, all.size() - maxMessages);
        StringBuilder prompt =
                new StringBuilder("你是重视边界、现实行动和长期变化的关系顾问。以下是按时间排序的会话，请回答最后一个用户问题。\n");
        users.findById(userId)
                .ifPresent(
                        user ->
                                prompt.append("\n用户称呼：")
                                        .append(value(user.getNickname()))
                                        .append("；当前情绪：")
                                        .append(value(user.getEmotionStatus()))
                                        .append("。请据此调整称呼、语气、信息密度和行动难度，不要机械复述情绪标签。\n"));
        profiles.findByUserId(userId)
                .ifPresent(
                        profile ->
                                prompt.append("\n用户长期关系档案（仅用于个性化，不要机械复述，也不要越过边界）：\n关系状态：")
                                        .append(value(profile.getRelationshipStatus()))
                                        .append("；相处时长：")
                                        .append(
                                                profile.getRelationshipMonths() == null
                                                        ? "未填写"
                                                        : profile.getRelationshipMonths() + "个月")
                                        .append("；沟通风格：")
                                        .append(value(profile.getCommunicationStyle()))
                                        .append("；长期关注：")
                                        .append(value(profile.getConcerns()))
                                        .append("；偏好：")
                                        .append(value(profile.getPreferences()))
                                        .append("；明确边界：")
                                        .append(value(profile.getBoundaries()))
                                        .append("。\n回答时结合长期目标、偏好与边界，并给出足够小、可执行且可用于后续行动规划的下一步。\n"));
        prompt.append("\n会话：\n");
        // 逐条拼接历史，超出 maxChars 则停止追加更早的消息
        for (AiMessage message : all.subList(from, all.size())) {
            String line =
                    ("USER".equals(message.getRole()) ? "用户" : "顾问")
                            + "："
                            + message.getContent()
                            + "\n";
            if (prompt.length() + line.length() <= maxChars) prompt.append(line);
        }
        if (!sources.isEmpty()) {
            // 注入检索到的知识片段，供模型引用
            prompt.append("\n可引用的知识片段：\n");
            for (KnowledgeService.Source source : sources) {
                prompt.append("[《")
                        .append(source.documentName())
                        .append("》·")
                        .append(source.section())
                        .append("] ")
                        .append(source.content())
                        .append("\n");
            }
        }
        return prompt.toString();
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

    /** 空值兜底，展示为"未填写" */
    private String value(String text) {
        return text == null || text.isBlank() ? "未填写" : text.trim();
    }

    /** 粗略估算 token 数：按中文约 3.5 字符/token，至少 1 */
    private int tokens(String text) {
        return text == null ? 0 : Math.max(1, (int) Math.ceil(text.length() / 3.5));
    }

    /** 生成任务并发控制 key：userId:conversationId，保证同会话同用户唯一 */
    private String key(Long conversationId, Long userId) {
        return userId + ":" + conversationId;
    }

    /** 发送一个 SSE 事件，客户端断开时静默忽略（最终状态由连接回调落库） */
    private void event(SseEmitter emitter, String name, Object data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data));
        } catch (IOException ignored) {
            // Connection callbacks will persist final state.
        }
    }

    /**
     * 一次流式生成任务的运行时上下文。
     * 持有 SSE 发射器、累积文本、assistant 消息、缓存键、起始时间与 Reactor 订阅句柄。
     */
    private static final class Generation {
        private final SseEmitter emitter;
        /** 流式过程中逐段累积的回复文本 */
        private final StringBuilder text = new StringBuilder();
        private final AiMessage assistant;
        /** Redis 模型结果缓存键，成功后写回 */
        private final String modelCacheKey;
        /** 任务开始的纳秒时间戳，用于 TTFT 与总耗时统计 */
        private final long startedAt = System.nanoTime();
        /** 是否已收到首字，保证 TTFT 只计一次 */
        private final AtomicBoolean firstToken = new AtomicBoolean();
        /** Reactor 订阅句柄，stop 时 dispose 以中断流；volatile 保证跨线程可见 */
        private volatile Disposable disposable;

        private Generation(SseEmitter emitter, AiMessage assistant, String modelCacheKey) {
            this.emitter = emitter;
            this.assistant = assistant;
            this.modelCacheKey = modelCacheKey;
        }
    }
}
