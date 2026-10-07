package com.heartpilot.module.agent.requirement;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.util.List;

/**
 * 地点见面需求的业务实体。
 * 归入 StructuredRequirement 的 place 字段，与四类约束并列，供代码校验器与方案生成使用。
 *
 * @param city 目标城市
 * @param startPoint 起点（家/公司等，可为空）
 * @param startTime 开始时间（HH:mm，可为空）
 * @param latestReturnTime 最晚返程时间（HH:mm，可为空）
 * @param transportMode 出行方式（打车/地铁/自驾等，可为空）
 * @param partySize 同行人数
 * @param mustVisit 必去点位（硬性）
 * @param recommendedVisit 推荐点位（优先偏好，非硬性）
 * @param forbiddenPlaces 禁止点位（排除）
 * @param stayMinutesPerPlace 每个点位预计停留分钟数
 * @param budgetMin 预算下限（元，可为空）
 * @param budgetMax 预算上限（元，可为空）
 * @param budgetText 预算原始文本（展示用）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PlaceRequirement(
        String city,
        String startPoint,
        String startTime,
        String latestReturnTime,
        String transportMode,
        Integer partySize,
        List<String> mustVisit,
        List<String> recommendedVisit,
        List<String> forbiddenPlaces,
        Integer stayMinutesPerPlace,
        BigDecimal budgetMin,
        BigDecimal budgetMax,
        String budgetText) {

    public PlaceRequirement {
        mustVisit = safe(mustVisit);
        recommendedVisit = safe(recommendedVisit);
        forbiddenPlaces = safe(forbiddenPlaces);
    }

    private static List<String> safe(List<String> values) {
        return values == null ? List.of() : values.stream().filter(v -> v != null && !v.isBlank()).toList();
    }

    public String summary() {
        StringBuilder out = new StringBuilder();
        out.append("- **地点**：").append(blankTo(city, "未指定")).append("\n");
        out.append("- **时间窗口**：")
                .append(blankTo(startTime, "未指定"))
                .append(" → ")
                .append(blankTo(latestReturnTime, "未限定"))
                .append("（每点停留约 ")
                .append(stayMinutesPerPlace == null ? "未指定" : stayMinutesPerPlace + " 分钟")
                .append("）\n");
        out.append("- **出行方式**：").append(blankTo(transportMode, "未指定")).append("\n");
        out.append("- **同行人数**：").append(partySize == null ? "未指定" : partySize + " 人").append("\n");
        out.append("- **必去点位**：").append(mustVisit.isEmpty() ? "无" : String.join("；", mustVisit)).append("\n");
        out.append("- **推荐点位**：").append(recommendedVisit.isEmpty() ? "无" : String.join("；", recommendedVisit)).append("\n");
        out.append("- **禁止点位**：").append(forbiddenPlaces.isEmpty() ? "无" : String.join("；", forbiddenPlaces)).append("\n");
        out.append("- **预算**：").append(budgetText == null || budgetText.isBlank() ? "未限定" : budgetText).append("\n");
        return out.toString();
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
