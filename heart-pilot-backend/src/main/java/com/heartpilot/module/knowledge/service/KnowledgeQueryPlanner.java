package com.heartpilot.module.knowledge.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** 检索前的轻量级查询改写与分类/场景路由。 */
@Component
public class KnowledgeQueryPlanner {
    /** 句首客套开场白正则（如"请问""我想问一下"），改写查询时统一剥除以提升召回。 */
    private static final Pattern FILLER =
            Pattern.compile("^(请问|我想问一下|想咨询一下|能不能告诉我|帮我看看)[，,：:\\s]*");
    /** 按非字母数字字符切词的正则，用于提取检索关键词。 */
    private static final Pattern TOKEN_SPLIT = Pattern.compile("[^\\p{L}\\p{N}]+");

    /**
     * 知识查询规划器入口：对用户原始问句做预处理、意图分类、场景识别，生成查询Plan，供给RAG检索使用
     * @param rawQuery 用户原始输入问句
     * @return Plan查询计划对象，包含清洗后的查询文本、分类、场景、提取关键词
     */
    public Plan plan(String rawQuery) {
        String normalized = rawQuery == null ? "" : rawQuery.strip().replaceAll("\\s+", " ");
        String rewritten = FILLER.matcher(normalized).replaceFirst("");
        // 根据精简后的问句，推断查询类别
        String category = inferCategory(rewritten);
        // 结合文本和类别，推断业务场景
        String scenario = inferScenario(rewritten, category);
        return new Plan(
                rewritten.isBlank() ? normalized : rewritten, category, scenario, terms(rewritten));
    }

    /** 基于关键词规则把问句归到知识分类白名单，未命中任何规则返回 null（不过滤分类）。 */
    private String inferCategory(String text) {
        if (containsAny(text, "暴力", "家暴", "威胁", "自伤", "自杀", "跟踪", "强迫")) return "风险与安全";
        if (containsAny(text, "分手", "结束关系", "断联", "复合")) return "分手与结束关系";
        if (containsAny(text, "边界", "同意", "拒绝", "隐私", "越界")) return "边界与同意";
        if (containsAny(text, "吵架", "冲突", "道歉", "修复", "冷战")) return "冲突与修复";
        if (containsAny(text, "微信", "消息", "已读", "聊天软件", "社交媒体", "朋友圈")) return "数字沟通";
        if (containsAny(text, "行动", "步骤", "计划", "怎么做")) return "行动设计";
        if (containsAny(text, "暧昧", "约会", "恋爱", "婚姻", "关系阶段")) return "关系阶段";
        if (containsAny(text, "沟通", "表达", "倾听", "对话")) return "沟通基础";
        return null;
    }

    /** 按已确定的分类推断业务场景标签；无对应场景时返回 null。 */
    private String inferScenario(String text, String category) {
        if ("风险与安全".equals(category)) return "高风险";
        if ("冲突与修复".equals(category)) return "冲突修复";
        if ("数字沟通".equals(category)) return "数字沟通";
        return null;
    }

    /**
     * 从清洗后的查询文本中提取检索关键词terms，用于RAG检索，生成关键词集合
     * @param text 经过预处理、去掉开场白后的用户问句
     * @return 关键词列表，最多返回30个
     */
    private List<String> terms(String text) {
        Set<String> values = new LinkedHashSet<>();
        // 使用TOKEN_SPLIT正则把文本切分成单词流；全部转小写，消除大小写差异
        TOKEN_SPLIT
                .splitAsStream(text.toLowerCase(Locale.ROOT))
                // 过滤掉长度小于2的词，单字不参与检索
                .filter(value -> value.length() >= 2)
                .forEach(
                        value -> {
                            values.add(value);
                            // 判断词条是否包含中日韩CJK汉字，并且词条长度大于2
                            if (containsCjk(value) && value.length() > 2) {
                                // 滑动窗口：取2字子串
                                for (int index = 0;
                                        index < value.length() - 1 && values.size() < 30;
                                        index++) {
                                    values.add(value.substring(index, index + 2));
                                }
                            }
                        });
        return new ArrayList<>(values).stream().limit(30).toList();
    }

    /** 判断词条是否包含 CJK 统一汉字（U+4E00–U+9FFF），用于决定是否补充二字滑动窗口。 */
    private boolean containsCjk(String value) {
        return value.codePoints().anyMatch(point -> point >= 0x4E00 && point <= 0x9FFF);
    }

    private boolean containsAny(String text, String... values) {
        for (String value : values) if (text.contains(value)) return true;
        return false;
    }

    /** 查询规划结果：改写后的查询文本、分类、场景标签与关键词词表，供检索与缓存键使用。 */
    public record Plan(
            String rewrittenQuery, String category, String scenario, List<String> terms) {}
}
