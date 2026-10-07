package com.heartpilot.module.agent.service.impl;

import com.heartpilot.module.agent.entity.AgentTask;
import com.heartpilot.module.agent.service.AgentRequirementAnalysisService;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 把用户自由表述的任务上下文，转换为一小撮结构化、可检索的意图关键词。
 * Converts free-form task context into a small, structured set of searchable intents.
 *
 * 可靠性设计要点：
 * - 大模型未配置 API Key 或调用失败时，自动降级为规则方案：直接用目标 + 问题原文作为关键词，
 *   保证检索仍能进行（Analysis.aiGenerated=false 标记降级）
 * - 无论模型结果还是降级结果，都经过 sanitize 清洗：去标点、长度过滤、
 *   剔除"目标/地点/预算/分析"等系统词、全局去重，防止脏关键词污染地图检索
 */
@Service
public class AgentRequirementAnalysisServiceImpl implements AgentRequirementAnalysisService {
    /** 固定系统提示词：约束模型只输出归一化的类别关键词，不解释、不输出系统词 */
    private static final String SYSTEM_PROMPT =
            """
            你是关系行动需求分析器，负责把用户的自由表述转换为可检索、可执行的结构化意图关键词。
            这些关键词后续会用于地图地点检索、联网商品/礼物检索、网页资料检索等不同链路，
            因此不要只按"找地点"理解用户，要识别全部行动意图。
            必须遵守：
            1. 同时分析"计划目标""送礼/沟通/练习等背景补充""需要逐项回答的问题"；
               城市只作为范围，预算只作为约束。
            2. 识别全部输入中的独立意图，每个意图只输出一个通用、简短、可搜索的关键词，并全局去重。
            3. 将口语归一成可检索概念：
               - 地点类："哪里有安静的咖啡馆"→"咖啡馆"；"想住一晚"→"酒店"；
               - 礼物类："送女朋友生日礼物，她喜欢喝茶"→保留"生日礼物""茶"这类商品/偏好词；
               - 消息/沟通类：保留"道歉""表白""缓和关系"等沟通目的词；
               - 自我练习类：保留"情绪复盘""主动表达"等练习主题词。
            4. 不得输出"目标、优先、当前有效参数、初始目标、地点、预算、问题、分析"等系统词。
            5. 只返回符合给定结构的内容，不输出自然语言解释。
            6. 用户明确输入具体店名、品牌、商品或设施名称时可以原样保留；不得自行编造或主动扩展具体名称。
            """;

    /** 预配置系统提示词的 ChatClient */
    private final ChatClient client;
    /** 是否真正启用大模型分析（API Key 已配置且非占位值）；false 时全程走规则降级 */
    private final boolean enabled;

    /**
     * 构造 ChatClient 并根据 API Key 是否有效决定是否启用 AI 分析。
     * @param model DashScope 聊天模型（@Qualifier 指定）
     * @param apiKey DashScope 密钥，空或 "not-configured" 视为未启用
     */
    public AgentRequirementAnalysisServiceImpl(
            @Qualifier("dashscopeChatModel") ChatModel model,
            @Value("${spring.ai.dashscope.api-key:}") String apiKey) {
        this.client = ChatClient.builder(model).defaultSystem(SYSTEM_PROMPT).build();
        this.enabled = apiKey != null && !apiKey.isBlank() && !"not-configured".equals(apiKey);
    }

    /**
     * 分析任务需求，产出结构化检索意图。
     * 优先调用大模型抽取去重类别词；未启用 AI、模型返回空结果或抛异常时，
     * 降级为目标 + 问题原文（经清洗），并标记 aiGenerated=false。
     *
     * @param task 任务实体
     * @param city 城市范围（仅作为约束传给模型）
     * @param budget 预算文本
     * @param questions 需要逐项回答的问题
     * @param revisions 历次修改要求
     * @return 检索文本 + 关键词列表 + 是否由 AI 生成
     */
    @Override
    public Analysis analyze(
            AgentTask task,
            String city,
            String budget,
            List<String> questions,
            List<String> revisions) {
        // 降级输入：目标 + 全部问题，先做一遍清洗作为兜底
        List<String> fallbackInputs = new ArrayList<>();
        fallbackInputs.add(task.getObjective());
        fallbackInputs.addAll(questions);
        List<String> fallback = sanitize(fallbackInputs);
        if (!enabled) return new Analysis(String.join("\n", fallback), fallback, false);
        try {
            // 让模型结构化输出 ModelAnalysis，并映射为实体
            ModelAnalysis result =
                    client.prompt()
                            .user(
                                    """
                                    计划目标：%s
                                    地点范围：%s
                                    预算：%s
                                    历次补充要求：%s
                                    需要逐项回答的问题：
                                    %s

                                    请合并分析目标与全部问题，最终 keywords 只返回去重后的通用类别词。
                                    """
                                            .formatted(
                                                    task.getObjective(),
                                                    city,
                                                    budget,
                                                    revisions.isEmpty()
                                                            ? "无"
                                                            : String.join("；", revisions),
                                                    String.join(
                                                            "\n",
                                                            java.util.stream.IntStream.range(
                                                                            0, questions.size())
                                                                    .mapToObj(
                                                                            index ->
                                                                                    (index + 1)
                                                                                            + ". "
                                                                                            + questions
                                                                                                    .get(
                                                                                                            index))
                                                                    .toList())))
                            .call()
                            .entity(ModelAnalysis.class);
            List<String> keywords = sanitizeModel(result);
            // 模型没抽出有效关键词，也降级
            if (keywords.isEmpty())
                return new Analysis(String.join("\n", fallback), fallback, false);
            return new Analysis(String.join("\n", keywords), keywords, true);
        } catch (Exception ignored) {
            // 模型调用失败：走规则降级，不影响主流程
            return new Analysis(String.join("\n", fallback), fallback, false);
        }
    }

    /**
     * 清洗模型返回的关键词：最多取 15 个，再统一过 sanitize 去重去脏。
     */
    private static List<String> sanitizeModel(ModelAnalysis result) {
        if (result == null || result.keywords() == null) return List.of();
        return sanitize(result.keywords().stream().limit(15).toList());
    }

    /**
     * 通用关键词清洗：按标点切分、长度过滤（2~20 字）、剔除系统词、LinkedHashSet 保序去重。
     * 同时用于模型输出与规则降级输入，保证两路结果质量一致。
     */
    static List<String> sanitize(List<String> values) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (values == null) return List.of();
        for (String raw : values) {
            // 把中英文标点、换行替换为空格后再切词
            String value = raw == null ? "" : raw.trim().replaceAll("[，,；;。！？?\\n]+", " ");
            for (String part : value.split("\\s+")) {
                String keyword = part.trim();
                // 过短或过长的词不作为检索类别
                if (keyword.length() < 2 || keyword.length() > 20) continue;
                // 剔除"目标/优先/地点/预算/问题/分析"等系统词
                if (keyword.matches(".*(?:当前有效参数|初始目标|最高优先级|目标|优先|地点|预算|问题|分析).*")) continue;
                result.add(keyword);
            }
        }
        return new ArrayList<>(result);
    }
}
