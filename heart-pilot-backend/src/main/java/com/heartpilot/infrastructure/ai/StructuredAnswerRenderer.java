package com.heartpilot.infrastructure.ai;

import com.heartpilot.infrastructure.ai.ConversationClassifier.Route;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/** 把大模型输出的自由文本规整为固定六段式决策助手结构（缺段时用默认文案补齐）。 */
@Component
public class StructuredAnswerRenderer {
    /** 约定的六段式章节标题，顺序即输出顺序。 */
    private static final String[] SECTIONS = {"已知事实", "仍不确定", "可选做法", "每种做法的风险", "建议补充问题", "引用依据"};

    /**
     * 规整模型输出：若已包含全部六个章节则原样返回；否则按固定顺序补全缺失章节并套用各段默认文案。
     */
    public String normalize(String raw, Route route, boolean hasSources) {
        String content = raw == null ? "" : raw.strip();
        Map<String, String> parsed = parse(content);
        // 六个章节都齐了，说明模型已按约定结构输出，直接放行
        if (parsed.size() == SECTIONS.length) return content;

        Map<String, String> values = new LinkedHashMap<>();
        values.put("已知事实", parsed.getOrDefault("已知事实", "- 目前只确认了你在本轮明确描述的情况。"));
        values.put("仍不确定", parsed.getOrDefault("仍不确定", "- 仍缺少对话原文、发生频率和你希望达到的结果。"));
        // 需要先澄清时，默认"可选做法"引导用户补充信息，而不是直接给行动建议
        String defaultAction =
                route == Route.CLARIFY
                        ? "- 先补充关键事实，再比较具体选择。"
                        : (content.isBlank() ? "- 先确认事实，再选择一次清晰、尊重边界的沟通。" : content);
        values.put("可选做法", parsed.getOrDefault("可选做法", defaultAction));
        values.put("每种做法的风险", parsed.getOrDefault("每种做法的风险", "- 信息不足时直接行动，可能误判对方意图或忽略自己的边界。"));
        values.put("建议补充问题", parsed.getOrDefault("建议补充问题", "- 实际发生了什么？你最希望解决的具体问题是什么？"));
        // 是否有来源，决定"引用依据"段的默认文案
        values.put(
                "引用依据",
                parsed.getOrDefault(
                        "引用依据", hasSources ? "- 见正文中的句内来源编号。" : "- 当前知识库没有可靠依据；以上未引用部分仅为一般性建议。"));
        // 统一渲染为 "## 章节名\n正文" 的 Markdown 结构
        StringBuilder result = new StringBuilder();
        values.forEach(
                (name, value) ->
                        result.append("## ")
                                .append(name)
                                .append('\n')
                                .append(value.strip())
                                .append("\n\n"));
        return result.toString().strip();
    }

    /** 按 Markdown 标题行切分模型输出，识别命中约定章节名的部分并收集其正文。 */
    private Map<String, String> parse(String content) {
        Map<String, String> result = new LinkedHashMap<>();
        String current = null;
        StringBuilder value = new StringBuilder();
        for (String line : content.split("\\R")) {
            String candidate = line.replaceFirst("^#{1,6}\\s*", "").strip();
            // 仅当该行是标题且标题名属于约定章节时，才视为一个新章节起点
            boolean heading =
                    line.strip().startsWith("#")
                            && java.util.Arrays.asList(SECTIONS).contains(candidate);
            if (heading) {
                if (current != null) result.put(current, value.toString().strip());
                current = candidate;
                value.setLength(0);
            } else if (current != null) {
                value.append(line).append('\n');
            }
        }
        if (current != null) result.put(current, value.toString().strip());
        return result;
    }
}
