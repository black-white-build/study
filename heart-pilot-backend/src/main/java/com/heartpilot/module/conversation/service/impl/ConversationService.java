package com.heartpilot.module.conversation.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.heartpilot.common.exception.ApiException;
import com.heartpilot.infrastructure.ai.RelationshipAiClient;
import com.heartpilot.module.agent.service.impl.RedisResultCacheService;
import com.heartpilot.module.conversation.entity.AiConversation;
import com.heartpilot.module.conversation.entity.AiMessage;
import com.heartpilot.module.conversation.entity.enums.AiMessageStatus;
import com.heartpilot.module.conversation.repository.ConversationRepository;
import com.heartpilot.module.conversation.repository.MessageRepository;
import com.heartpilot.module.knowledge.service.impl.KnowledgeService;
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

@Service
public class ConversationService {
    private final ConversationRepository conversations;
    private final MessageRepository messages;
    private final ProfileRepository profiles;
    private final AppUserRepository users;
    private final RelationshipAiClient ai;
    private final KnowledgeService knowledge;
    private final ObjectMapper json;
    private final MeterRegistry metrics;
    private final int maxMessages;
    private final int maxChars;
    private final int maxRetries;
    private final RedisResultCacheService cache;
    private final double inputCnyPerMillionTokens;
    private final double outputCnyPerMillionTokens;
    private final Map<String, Generation> active = new ConcurrentHashMap<>();

    public ConversationService(
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

    public Page<AiConversation> list(Long userId, Pageable pageable) {
        return conversations.findByUserIdAndArchivedFalse(userId, pageable);
    }

    public AiConversation create(Long userId, String title) {
        AiConversation conversation = new AiConversation();
        conversation.setUserId(userId);
        conversation.setTitle(title == null || title.isBlank() ? "新的倾诉" : title.trim());
        conversation.setLastMessageAt(Instant.now());
        return conversations.save(conversation);
    }

    public AiConversation get(Long id, Long userId) {
        return conversations
                .findByIdAndUserId(id, userId)
                .orElseThrow(() -> ApiException.notFound("会话不存在"));
    }

    public List<AiMessage> history(Long id, Long userId) {
        get(id, userId);
        return messages.findByConversationIdAndUserIdOrderByCreatedAtAsc(id, userId);
    }

    public Page<AiMessage> history(Long id, Long userId, Pageable pageable) {
        get(id, userId);
        return messages.findByConversationIdAndUserId(id, userId, pageable);
    }

    @Transactional
    public AiConversation rename(Long id, Long userId, String title) {
        AiConversation conversation = get(id, userId);
        conversation.setTitle(title.strip());
        return conversation;
    }

    @Transactional
    public void delete(Long id, Long userId) {
        AiConversation conversation = get(id, userId);
        stop(id, userId);
        messages.deleteByConversationIdAndUserId(id, userId);
        conversations.delete(conversation);
    }

    public SseEmitter send(Long conversationId, Long userId, String content, Long regeneratedFrom) {
        AiConversation conversation = get(conversationId, userId);
        String normalizedContent = content.strip();
        String key = key(conversationId, userId);
        if (active.containsKey(key)) {
            throw ApiException.conflict("GENERATION_ACTIVE", "当前会话正在生成");
        }

        AiMessage userMessage = new AiMessage();
        userMessage.setConversationId(conversationId);
        userMessage.setUserId(userId);
        userMessage.setRole("USER");
        userMessage.setContent(normalizedContent);
        userMessage.setInputTokens(tokens(normalizedContent));
        userMessage.setModel(conversation.getModel());
        userMessage.setRegeneratedFromId(regeneratedFrom);
        messages.save(userMessage);

        if (history(conversationId, userId).size() <= 1) {
            conversation.setTitle(
                    normalizedContent.substring(0, Math.min(24, normalizedContent.length())));
        }
        conversation.setLastMessageAt(Instant.now());
        conversations.save(conversation);

        List<KnowledgeService.Source> sources = knowledge.retrieve(normalizedContent, 4);
        AiMessage assistant = new AiMessage();
        assistant.setConversationId(conversationId);
        assistant.setUserId(userId);
        assistant.setRole("ASSISTANT");
        assistant.setContent("");
        assistant.setStatus(AiMessageStatus.STREAMING);
        assistant.setModel(conversation.getModel());
        try {
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
            assistant.setSourcesJson("[]");
        }
        assistant = messages.save(assistant);

        String prompt = buildPrompt(conversationId, userId, sources);
        assistant.setInputTokens(tokens(prompt));
        assistant.setInputCostMicros(
                estimateCost(assistant.getInputTokens(), inputCnyPerMillionTokens));
        assistant.setEstimatedCostMicros(assistant.getInputCostMicros());
        messages.save(assistant);

        String modelCacheKey = userId + "|" + conversation.getModel() + "|" + prompt;
        SseEmitter emitter = new SseEmitter(180_000L);
        Generation generation = new Generation(emitter, assistant, modelCacheKey);
        active.put(key, generation);
        AiMessage savedAssistant = assistant;
        Optional<String> cachedResult = cache.getModelResult(modelCacheKey);
        if (cachedResult.isPresent()) {
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
        generation.disposable =
                ai.stream(prompt)
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
        emitter.onTimeout(() -> stop(conversationId, userId));
        emitter.onError(error -> stop(conversationId, userId));
        return emitter;
    }

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
        AiMessage userMessage = "USER".equals(target.getRole()) ? target : null;
        for (int i = index - 1; userMessage == null && i >= 0; i--) {
            if ("USER".equals(all.get(i).getRole())) userMessage = all.get(i);
        }
        if (userMessage == null) throw ApiException.badRequest("找不到可重新生成的用户消息");
        return send(conversationId, userId, userMessage.getContent(), messageId);
    }

    public boolean stop(Long conversationId, Long userId) {
        Generation generation = active.remove(key(conversationId, userId));
        if (generation == null) return false;
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
        if (!generation.assistant.isCacheHit() && generation.text.length() > 0) {
            cache.putModelResult(generation.modelCacheKey, generation.text.toString());
        }
        generation.emitter.complete();
        recordDuration(generation, "success");
    }

    private void finishError(String key, Generation generation, Throwable error) {
        if (!active.remove(key, generation)) return;
        generation.assistant.setContent(generation.text.toString());
        generation.assistant.setStatus(AiMessageStatus.FAILED);
        String message = error.getMessage() == null ? "模型调用失败" : error.getMessage();
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

    private void persist(Generation generation, AiMessageStatus status) {
        generation.assistant.setContent(generation.text.toString());
        generation.assistant.setOutputTokens(tokens(generation.text.toString()));
        long outputCost =
                estimateCost(generation.assistant.getOutputTokens(), outputCnyPerMillionTokens);
        if (generation.assistant.isCacheHit()) {
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
        generation.assistant.setProviderLatencyMs(
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - generation.startedAt));
        generation.assistant.setStatus(status);
        messages.save(generation.assistant);
    }

    private long estimateCost(int tokenCount, double cnyPerMillionTokens) {
        // One CNY equals one million micros, so the per-million-token factors cancel out.
        return Math.max(0, Math.round(tokenCount * cnyPerMillionTokens));
    }

    private String buildPrompt(
            Long conversationId, Long userId, List<KnowledgeService.Source> sources) {
        List<AiMessage> all =
                messages.findByConversationIdAndUserIdOrderByCreatedAtAsc(conversationId, userId);
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
                                        .append("。\n回答时结合长期目标、偏好与边界，并给出足够小、可执行且可在成长计划中打卡的下一步。\n"));
        prompt.append("\n会话：\n");
        for (AiMessage message : all.subList(from, all.size())) {
            String line =
                    ("USER".equals(message.getRole()) ? "用户" : "顾问")
                            + "："
                            + message.getContent()
                            + "\n";
            if (prompt.length() + line.length() <= maxChars) prompt.append(line);
        }
        if (!sources.isEmpty()) {
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

    private void recordFirstToken(Generation generation) {
        if (generation.firstToken.compareAndSet(false, true)) {
            Timer.builder("heartpilot.chat.time_to_first_token")
                    .register(metrics)
                    .record(System.nanoTime() - generation.startedAt, TimeUnit.NANOSECONDS);
        }
    }

    private void recordDuration(Generation generation, String outcome) {
        Timer.builder("heartpilot.chat.generation.duration")
                .tag("outcome", outcome)
                .register(metrics)
                .record(System.nanoTime() - generation.startedAt, TimeUnit.NANOSECONDS);
    }

    private String value(String text) {
        return text == null || text.isBlank() ? "未填写" : text.trim();
    }

    private int tokens(String text) {
        return text == null ? 0 : Math.max(1, (int) Math.ceil(text.length() / 3.5));
    }

    private String key(Long conversationId, Long userId) {
        return userId + ":" + conversationId;
    }

    private void event(SseEmitter emitter, String name, Object data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data));
        } catch (IOException ignored) {
            // Connection callbacks will persist final state.
        }
    }

    private static final class Generation {
        private final SseEmitter emitter;
        private final StringBuilder text = new StringBuilder();
        private final AiMessage assistant;
        private final String modelCacheKey;
        private final long startedAt = System.nanoTime();
        private final AtomicBoolean firstToken = new AtomicBoolean();
        private volatile Disposable disposable;

        private Generation(SseEmitter emitter, AiMessage assistant, String modelCacheKey) {
            this.emitter = emitter;
            this.assistant = assistant;
            this.modelCacheKey = modelCacheKey;
        }
    }
}
