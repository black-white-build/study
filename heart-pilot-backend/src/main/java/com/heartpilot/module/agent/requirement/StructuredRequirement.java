package com.heartpilot.module.agent.requirement;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.util.List;

/**
 * 结构化需求对象（固定 Schema）。
 *
 * <p>自然语言经过需求解析 Agent 抽取后落地为四类约束 + 业务实体，
 * 后续所有校验与方案生成都以本对象为唯一约束来源，不再依赖原始文本。
 * 约束分类（核心设计）：
 * <ul>
 *   <li>hardConstraints（硬性约束）：必须满足，违反即为不可行；由代码校验器保证</li>
 *   <li>priorityPreferences（优先偏好）：尽量满足，排序时优先</li>
 *   <li>optionalEnhancements（可选加分项）：有余力才做，不满足不影响可行性</li>
 *   <li>exclusions（排除黑名单）：坚决不做，方案不得出现</li>
 * </ul>
 * 地点与送礼共用本对象，业务实体分别放在 place / gift 子对象。
 *
 * @param type 需求类型（PLACE / GIFT）
 * @param hardConstraints 硬性约束列表
 * @param priorityPreferences 优先偏好列表
 * @param optionalEnhancements 可选加分项列表
 * @param exclusions 排除黑名单列表
 * @param place 地点业务实体（PLACE 类型时非 null）
 * @param gift 送礼业务实体（GIFT 类型时非 null）
 * @param aiGenerated 是否由大模型抽取（false = 规则降级抽取）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StructuredRequirement(
        RequirementType type,
        List<String> hardConstraints,
        List<String> priorityPreferences,
        List<String> optionalEnhancements,
        List<String> exclusions,
        PlaceRequirement place,
        GiftRequirement gift,
        boolean aiGenerated) {

    public StructuredRequirement {
        hardConstraints = safe(hardConstraints);
        priorityPreferences = safe(priorityPreferences);
        optionalEnhancements = safe(optionalEnhancements);
        exclusions = safe(exclusions);
    }

    private static List<String> safe(List<String> values) {
        return values == null ? List.of() : values.stream().filter(v -> v != null && !v.isBlank()).toList();
    }

    /** 生成面向用户/前端的约束摘要（Markdown 列表），四类约束分组展示 */
    public String summary() {
        StringBuilder out = new StringBuilder();
        out.append("### 结构化需求清单（").append(type == null ? "" : type.name()).append("）\n");
        appendSection(out, "硬性约束（必须满足）", hardConstraints);
        appendSection(out, "优先偏好（尽量满足）", priorityPreferences);
        appendSection(out, "可选加分项（有余力再做）", optionalEnhancements);
        appendSection(out, "排除黑名单（坚决不做）", exclusions);
        if (place != null) out.append(place.summary());
        if (gift != null) out.append(gift.summary());
        return out.toString().trim();
    }

    private void appendSection(StringBuilder out, String label, List<String> values) {
        out.append("- **").append(label).append("**");
        if (values.isEmpty()) {
            out.append("：无");
        } else {
            out.append("：").append(String.join("；", values));
        }
        out.append("\n");
    }
}
