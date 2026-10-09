package com.heartpilot.module.agent.requirement;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.util.List;

/**
 * 礼物表达需求的业务实体。 归入 StructuredRequirement 的 gift 字段。关键词生成、黑名单过滤、 多平台跳转链接均以本实体为约束来源。
 *
 * @param recipient 送礼对象（女朋友/朋友/家人等）
 * @param recipientAge 对象年龄（可为空）
 * @param occasion 场合（生日/道歉/感谢/纪念日等）
 * @param budgetMin 预算下限（元，可为空）
 * @param budgetMax 预算上限（元，可为空）
 * @param budgetText 预算原始文本（如"200到500元"）
 * @param stylePreferences 风格/喜好偏好（茶、游戏、香氛等）
 * @param forbiddenCategories 禁止品类（香水、奢侈品等）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GiftRequirement(
        String recipient,
        Integer recipientAge,
        String occasion,
        BigDecimal budgetMin,
        BigDecimal budgetMax,
        String budgetText,
        List<String> stylePreferences,
        List<String> forbiddenCategories) {

    public GiftRequirement {
        stylePreferences = safe(stylePreferences);
        forbiddenCategories = safe(forbiddenCategories);
    }

    private static List<String> safe(List<String> values) {
        return values == null
                ? List.of()
                : values.stream().filter(v -> v != null && !v.isBlank()).toList();
    }

    public String summary() {
        StringBuilder out = new StringBuilder();
        out.append("- **送礼对象**：").append(blankTo(recipient, "未指定"));
        if (recipientAge != null) out.append("（约 ").append(recipientAge).append(" 岁）");
        out.append("\n");
        out.append("- **场合**：").append(blankTo(occasion, "未指定")).append("\n");
        out.append("- **预算**：")
                .append(budgetText == null || budgetText.isBlank() ? "未限定" : budgetText)
                .append("\n");
        out.append("- **风格偏好**：")
                .append(stylePreferences.isEmpty() ? "无" : String.join("；", stylePreferences))
                .append("\n");
        out.append("- **禁止品类**：")
                .append(forbiddenCategories.isEmpty() ? "无" : String.join("；", forbiddenCategories))
                .append("\n");
        return out.toString();
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
