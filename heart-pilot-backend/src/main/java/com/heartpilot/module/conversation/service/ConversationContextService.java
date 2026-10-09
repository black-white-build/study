package com.heartpilot.module.conversation.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.heartpilot.module.conversation.entity.ConversationContextState;
import com.heartpilot.module.conversation.repository.ConversationContextStateRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 会话级短期记忆：抽取事实、假设、偏好与情绪，事实更正会覆盖旧值。 */
@Service
public class ConversationContextService {
    /** JSON 反序列化目标类型，避免每次读取都重复创建 TypeReference。 */
    private static final TypeReference<List<Map<String, Object>>> LIST_TYPE =
            new TypeReference<>() {};

    private final ConversationContextStateRepository states;
    private final ObjectMapper json;

    public ConversationContextService(
            ConversationContextStateRepository states, ObjectMapper json) {
        this.states = states;
        this.json = json;
    }

    /** 根据本轮用户输入更新会话记忆，不存在则新建，返回更新后的记忆快照。 */
    @Transactional
    public Snapshot update(Long conversationId, Long userId, Long messageId, String input) {
        // 根据会话ID+用户ID查询会话上下文状态记录
        ConversationContextState state =
                states.findByConversationIdAndUserId(conversationId, userId)
                        // 如果查询结果为空（这个会话还没有上下文状态）就新建一个会话上下文状态对象
                        .orElseGet(
                                () -> {
                                    ConversationContextState created =
                                            new ConversationContextState();
                                    created.setConversationId(conversationId);
                                    created.setUserId(userId);
                                    return created;
                                });

        List<Map<String, Object>> facts = read(state.getFactsJson());
        List<Map<String, Object>> assumptions = read(state.getAssumptionsJson());
        List<Map<String, Object>> preferences = read(state.getPreferencesJson());
        List<Map<String, Object>> emotions = read(state.getEmotionsJson());

        // 将用户输入文本，按中文句号、感叹号、问号、换行符切分成一句一句
        for (String raw : input.split("[。！？!?\\n]+")) {
            String sentence = raw.strip();
            if (sentence.isBlank()) continue;
            // 命中更正类措辞时，以新句子覆盖同 key 的旧事实
            boolean correction = containsAny(sentence, "更正", "刚才说错", "其实", "不是", "应该是");
            // 该句子是否已经归类到某一类记忆
            boolean categorized = false;
            if (correction) {
                putFact(facts, sentence, messageId, true);
                categorized = true;
            }
            if (containsAny(sentence, "我觉得", "我猜", "可能", "也许", "是不是", "看起来")) {
                append(assumptions, "assumption", sentence, messageId);
                categorized = true;
            }
            if (containsAny(sentence, "我希望", "我想要", "我不想", "我更喜欢", "我倾向")) {
                append(preferences, "preference", sentence, messageId);
                categorized = true;
            }
            if (containsAny(sentence, "我很难过", "我好难过", "我生气", "我焦虑", "我害怕", "我开心", "我委屈", "我崩溃")) {
                append(emotions, "emotion", sentence, messageId);
                categorized = true;
            }
            // 未命中任何分类的句子，默认归入事实
            if (!categorized) putFact(facts, sentence, messageId, false);
        }

        // 每类只保留最近 20 条，再序列化回库
        state.setFactsJson(write(limit(facts)));
        state.setAssumptionsJson(write(limit(assumptions)));
        state.setPreferencesJson(write(limit(preferences)));
        state.setEmotionsJson(write(limit(emotions)));
        states.save(state);
        return snapshot(state);
    }

    /** 读取会话记忆快照，无记录时返回四类均为空的空快照。 */
    public Snapshot get(Long conversationId, Long userId) {
        return states.findByConversationIdAndUserId(conversationId, userId)
                .map(this::snapshot)
                .orElseGet(() -> new Snapshot(List.of(), List.of(), List.of(), List.of()));
    }

    /** 删除指定会话的全部记忆。 */
    @Transactional
    public void delete(Long conversationId, Long userId) {
        states.deleteByConversationIdAndUserId(conversationId, userId);
    }

    /** 写入一条事实；更正时先按 key 移除旧事实，普通事实按 value 去重。 */
    private void putFact(
            List<Map<String, Object>> facts, String value, Long messageId, boolean correction) {
        String key = factKey(value);
        if (correction) facts.removeIf(item -> key.equals(item.get("key")));
        Map<String, Object> item = item(key, value, messageId);
        if (!correction && facts.stream().anyMatch(existing -> value.equals(existing.get("value"))))
            return;
        facts.add(item);
    }

    /** 追加一条非事实类记忆（假设/偏好/情绪），按 value 去重。 */
    private void append(
            List<Map<String, Object>> values, String key, String value, Long messageId) {
        if (values.stream().anyMatch(existing -> value.equals(existing.get("value")))) return;
        values.add(item(key, value, messageId));
    }

    /** 构造一条记忆条目：包含归类 key、原文 value、来源消息 ID 与写入时间戳。 */
    private Map<String, Object> item(String key, String value, Long messageId) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("key", key);
        item.put("value", value);
        item.put("sourceMessageId", messageId);
        item.put("updatedAt", Instant.now().toString());
        return item;
    }

    /** 为事实归类：常见关系/联系/见面状态用固定 key，其余用内容哈希兜底。 */
    private String factKey(String value) {
        if (containsAny(value, "分手", "交往", "恋爱", "结婚", "单身")) return "relationship_status";
        if (containsAny(value, "拉黑", "断联", "回复", "联系", "消息")) return "contact_status";
        if (containsAny(value, "见面", "约会")) return "meeting_status";
        // 上面都不匹配，则生成自定义key
        // 正则把句子开头的【更正/其实/刚才说错】这类前缀去掉，再计算hash
        return "event:"
                + Integer.toHexString(value.replaceFirst("^(更正|其实|刚才说错)[，,:：\\s]*", "").hashCode());
    }

    /** 每类记忆只保留最近 20 条，避免无限增长。 */
    private List<Map<String, Object>> limit(List<Map<String, Object>> values) {
        return new ArrayList<>(values.subList(Math.max(0, values.size() - 20), values.size()));
    }

    /** 读取 JSON 数组字符串，为空或解析失败时返回空列表。 */
    private List<Map<String, Object>> read(String value) {
        try {
            return new ArrayList<>(json.readValue(value == null ? "[]" : value, LIST_TYPE));
        } catch (Exception ignored) {
            return new ArrayList<>();
        }
    }

    /** 序列化为 JSON 写库；与 read() 静默容错不同，序列化失败直接抛异常中断保存。 */
    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("无法保存会话状态", exception);
        }
    }

    /** 把持久化实体中的四类 JSON 字段反序列化为对外快照 Snapshot。 */
    private Snapshot snapshot(ConversationContextState state) {
        return new Snapshot(
                read(state.getFactsJson()),
                read(state.getAssumptionsJson()),
                read(state.getPreferencesJson()),
                read(state.getEmotionsJson()));
    }

    private boolean containsAny(String text, String... values) {
        for (String value : values) if (text.contains(value)) return true;
        return false;
    }

    /** 会话记忆对外快照，按事实/假设/偏好/情绪四类分组。 */
    public record Snapshot(
            List<Map<String, Object>> facts,
            List<Map<String, Object>> assumptions,
            List<Map<String, Object>> preferences,
            List<Map<String, Object>> emotions) {}
}
