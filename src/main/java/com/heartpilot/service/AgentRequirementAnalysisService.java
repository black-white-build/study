package com.heartpilot.service;

import com.heartpilot.domain.AgentTask;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Converts free-form task context into a small, structured set of searchable intents. */
@Service
public class AgentRequirementAnalysisService {
    private static final String SYSTEM_PROMPT = """
            你是本地生活需求分析器，只负责把用户需求转换成可用于地图和网页检索的结构化意图。
            必须遵守：
            1. 同时分析“计划目标”和“需要逐项回答的问题”；地点只作为范围，预算只作为约束。
            2. 识别全部输入中的独立意图，每个意图只输出一个通用、简短、可搜索的类别关键词，并全局去重。
            3. 将口语和具体事物归一成一个上位检索概念。例如：“在哪里住/有什么地方住”只输出“住宿”；
               “玩三角洲行动”输出“游戏”；“去酒吧喝酒”只输出“酒吧”；“玩过山车”输出“过山车”；
               “有没有网吧”输出“网吧”。
            4. 不得输出“目标、优先、当前有效参数、初始目标、地点、预算、问题、分析”等系统词。
            5. 只返回符合给定结构的内容，不输出自然语言解释。
            6. 用户明确输入具体店名、品牌或设施名称时可以原样保留；不得自行编造或主动扩展具体名称、地址、同义词及子类型。
            """;

    private final ChatClient client;
    private final boolean enabled;

    public AgentRequirementAnalysisService(
            @Qualifier("dashscopeChatModel") ChatModel model,
            @Value("${spring.ai.dashscope.api-key:}") String apiKey) {
        this.client = ChatClient.builder(model).defaultSystem(SYSTEM_PROMPT).build();
        this.enabled = apiKey != null && !apiKey.isBlank() && !"not-configured".equals(apiKey);
    }

    public Analysis analyze(
            AgentTask task,
            String city,
            String budget,
            List<String> questions,
            List<String> revisions) {
        List<String> fallbackInputs = new ArrayList<>();
        fallbackInputs.add(task.getObjective());
        fallbackInputs.addAll(questions);
        List<String> fallback = sanitize(fallbackInputs);
        if (!enabled) return new Analysis(String.join("\n", fallback), fallback, false);
        try {
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
                                                    revisions.isEmpty() ? "无" : String.join("；", revisions),
                                                    String.join(
                                                            "\n",
                                                            java.util.stream.IntStream.range(0, questions.size())
                                                                    .mapToObj(index -> (index + 1) + ". " + questions.get(index))
                                                                    .toList())))
                            .call()
                            .entity(ModelAnalysis.class);
            List<String> keywords = sanitizeModel(result);
            if (keywords.isEmpty()) return new Analysis(String.join("\n", fallback), fallback, false);
            return new Analysis(String.join("\n", keywords), keywords, true);
        } catch (Exception ignored) {
            return new Analysis(String.join("\n", fallback), fallback, false);
        }
    }

    private static List<String> sanitizeModel(ModelAnalysis result) {
        if (result == null || result.keywords() == null) return List.of();
        return sanitize(result.keywords().stream().limit(15).toList());
    }

    static List<String> sanitize(List<String> values) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (values == null) return List.of();
        for (String raw : values) {
            String value = raw == null ? "" : raw.trim().replaceAll("[，,；;。！？?\\n]+", " ");
            for (String part : value.split("\\s+")) {
                String keyword = part.trim();
                if (keyword.length() < 2 || keyword.length() > 20) continue;
                if (keyword.matches(".*(?:当前有效参数|初始目标|最高优先级|目标|优先|地点|预算|问题|分析).*"))
                    continue;
                result.add(keyword);
            }
        }
        return new ArrayList<>(result);
    }

    public record Analysis(String searchText, List<String> keywords, boolean aiGenerated) {}

    public record ModelAnalysis(List<String> keywords) {}
}
