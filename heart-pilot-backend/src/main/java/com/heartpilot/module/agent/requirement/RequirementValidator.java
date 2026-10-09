package com.heartpilot.module.agent.requirement;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 独立代码约束校验器（纯 Java，不依赖大模型）。
 *
 * <p>拿到结构化需求对象后做硬性规则校验，识别需求冲突。设计原则： 可行性判断一律由代码给出确定性结论，LLM 只负责"理解自然语言"， 不负责"判断方案可不可行"——这正是根治 LLM
 * 幻觉与丢约束的关键。
 *
 * <p>当前规则集（可扩展）：
 *
 * <ul>
 *   <li>预算矛盾：预算下限 &gt; 上限 → BLOCKER
 *   <li>地点-时间资源冲突：必去点位数量 + 停留时长 + 通勤余量 超出时间窗口 → BLOCKER
 *   <li>排除冲突：排除黑名单与必去/推荐点位互相矛盾 → BLOCKER / WARNING
 *   <li>送礼-品类冲突：禁止品类与风格偏好互相矛盾 → BLOCKER
 *   <li>信息缺失类提示：必去点位为空、送礼对象缺失等 → WARNING
 * </ul>
 */
@Service
public class RequirementValidator {

    /** 每个点位默认停留分钟数（代码默认值，用户未指定时使用） */
    private static final int DEFAULT_STAY_MINUTES = 60;

    /** 地点间通勤余量：每个点位按此估算通勤耗时（分钟） */
    private static final int DEFAULT_COMMUTE_MINUTES = 25;

    /** 时间窗口最短合理时长（分钟），低于此值视为时间不足 */
    private static final int MIN_REASONABLE_WINDOW = 90;

    /**
     * 校验结构化需求，返回问题列表。
     *
     * @param requirement 结构化需求（null 时返回 BLOCKER 级通用问题）
     * @return 校验结果
     */
    public ValidationResult validate(StructuredRequirement requirement) {
        if (requirement == null) {
            return new ValidationResult(
                    List.of(
                            RequirementIssue.blocker(
                                    "REQUIREMENT_MISSING",
                                    "requirement",
                                    "未能解析出结构化需求，请补充更明确的目标与约束")));
        }
        List<RequirementIssue> issues = new ArrayList<>();
        validateBudget(requirement, issues);
        if (requirement.type() == RequirementType.PLACE) validatePlace(requirement, issues);
        if (requirement.type() == RequirementType.GIFT) validateGift(requirement, issues);
        return new ValidationResult(issues);
    }

    /** 预算矛盾：下限大于上限直接阻断 */
    private void validateBudget(StructuredRequirement requirement, List<RequirementIssue> issues) {
        BigDecimal min = null;
        BigDecimal max = null;
        if (requirement.place() != null) {
            min = requirement.place().budgetMin();
            max = requirement.place().budgetMax();
        }
        if (requirement.gift() != null) {
            min = requirement.gift().budgetMin();
            max = requirement.gift().budgetMax();
        }
        if (min != null && max != null && min.compareTo(max) > 0) {
            issues.add(
                    RequirementIssue.blocker(
                            "BUDGET_CONFLICT",
                            "budget",
                            "预算下限（"
                                    + min.toPlainString()
                                    + " 元）高于预算上限（"
                                    + max.toPlainString()
                                    + " 元），请调整预算区间"));
        }
    }

    /** 地点需求校验：时间资源、排除冲突、信息完整性 */
    private void validatePlace(StructuredRequirement requirement, List<RequirementIssue> issues) {
        PlaceRequirement place = requirement.place();
        if (place == null) {
            issues.add(
                    RequirementIssue.blocker(
                            "PLACE_ENTITY_MISSING", "place", "地点需求缺少业务实体，请重新填写目标与地点约束"));
            return;
        }
        // 时间资源校验：必去点位 × 停留时长 + 通勤余量 vs 时间窗口
        int mustCount = place.mustVisit().size();
        int totalVisit = mustCount + place.recommendedVisit().size();
        LocalTime start = parseTime(place.startTime());
        LocalTime end = parseTime(place.latestReturnTime());
        if (start != null && end != null) {
            int windowMinutes = minutesBetween(start, end);
            int stay =
                    place.stayMinutesPerPlace() == null
                            ? DEFAULT_STAY_MINUTES
                            : place.stayMinutesPerPlace();
            int requiredMinutes = mustCount * stay + totalVisit * DEFAULT_COMMUTE_MINUTES;
            if (windowMinutes < MIN_REASONABLE_WINDOW) {
                issues.add(
                        RequirementIssue.blocker(
                                "TIME_WINDOW_TOO_SHORT",
                                "place.latestReturnTime",
                                "时间窗口只有约 " + windowMinutes + " 分钟，不足以安排一次完整行程"));
            } else if (requiredMinutes > windowMinutes) {
                issues.add(
                        RequirementIssue.blocker(
                                "TIME_RESOURCE_OVERFLOW",
                                "place.latestReturnTime",
                                "预计需要约 "
                                        + requiredMinutes
                                        + " 分钟（"
                                        + totalVisit
                                        + " 个点位 × 停留/通勤），但最晚返程时间只剩约 "
                                        + windowMinutes
                                        + " 分钟；请删减点位或放宽时间"));
            } else if (requiredMinutes > windowMinutes - 60) {
                issues.add(
                        RequirementIssue.warning(
                                "TIME_BUFFER_TIGHT",
                                "place.latestReturnTime",
                                "时间余量偏紧（约 "
                                        + (windowMinutes - requiredMinutes)
                                        + " 分钟），建议减少 1 个点位留出缓冲"));
            }
        }
        if (mustCount == 0 && totalVisit == 0) {
            issues.add(
                    RequirementIssue.warning(
                            "NO_PLACE_INTENT",
                            "place.mustVisit",
                            "没有提取到任何必去或推荐点位，请补充想去的地方（如：咖啡馆、公园）"));
        }
        // 排除冲突：黑名单与必去点位直接矛盾 → 阻断；与推荐点位矛盾 → 警告
        for (String forbidden : place.forbiddenPlaces()) {
            for (String must : place.mustVisit()) {
                if (overlaps(forbidden, must)) {
                    issues.add(
                            RequirementIssue.blocker(
                                    "EXCLUSION_CONFLICTS_HARD",
                                    "place.forbiddenPlaces",
                                    "「" + forbidden + "」被列入禁止点位，但「" + must + "」又是必去点位，两者冲突请二选一"));
                }
            }
            for (String recommend : place.recommendedVisit()) {
                if (overlaps(forbidden, recommend)) {
                    issues.add(
                            RequirementIssue.warning(
                                    "EXCLUSION_CONFLICTS_PREFERENCE",
                                    "place.forbiddenPlaces",
                                    "「"
                                            + recommend
                                            + "」在推荐列表，但与禁止点位「"
                                            + forbidden
                                            + "」冲突，生成方案时会被排除"));
                }
            }
        }
        // 排除黑名单同时归入四类约束与实体时，合并成硬性约束级校验
        for (String exclusion : requirement.exclusions()) {
            for (String must : place.mustVisit()) {
                if (overlaps(exclusion, must)) {
                    issues.add(
                            RequirementIssue.blocker(
                                    "EXCLUSION_CONFLICTS_HARD",
                                    "exclusions",
                                    "排除项「" + exclusion + "」与必去点位「" + must + "」冲突，请调整"));
                }
            }
        }
    }

    /** 送礼需求校验：品类冲突、信息完整性 */
    private void validateGift(StructuredRequirement requirement, List<RequirementIssue> issues) {
        GiftRequirement gift = requirement.gift();
        if (gift == null) {
            issues.add(
                    RequirementIssue.blocker(
                            "GIFT_ENTITY_MISSING", "gift", "送礼需求缺少业务实体，请重新填写目标与送礼细节"));
            return;
        }
        for (String forbidden : gift.forbiddenCategories()) {
            for (String style : gift.stylePreferences()) {
                if (overlaps(forbidden, style)) {
                    issues.add(
                            RequirementIssue.blocker(
                                    "GIFT_CATEGORY_CONFLICT",
                                    "gift.forbiddenCategories",
                                    "「" + style + "」在风格偏好里，但又属于禁止品类「" + forbidden + "」，两者冲突请二选一"));
                }
            }
        }
        if (gift.recipient() == null || gift.recipient().isBlank()) {
            issues.add(
                    RequirementIssue.warning(
                            "GIFT_RECIPIENT_MISSING",
                            "gift.recipient",
                            "未提取到送礼对象（女朋友/家人/朋友等），关键词会偏向通用，建议补充"));
        }
        if ((gift.budgetText() == null || gift.budgetText().isBlank())
                && gift.budgetMin() == null
                && gift.budgetMax() == null) {
            issues.add(
                    RequirementIssue.warning(
                            "GIFT_BUDGET_MISSING", "gift.budgetText", "未提取到预算区间，推荐将更泛化，建议补充预算"));
        }
        if (gift.stylePreferences().isEmpty() && requirement.priorityPreferences().isEmpty()) {
            issues.add(
                    RequirementIssue.warning(
                            "GIFT_STYLE_MISSING",
                            "gift.stylePreferences",
                            "未提取到对方喜好与风格，建议补充（如：喜欢喝茶、喜欢游戏）"));
        }
    }

    /** 解析 HH:mm 时间，失败返回 null */
    private LocalTime parseTime(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return LocalTime.parse(value.trim().replace('：', ':'));
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    /** 两个时刻之间的分钟数（同日），end 早于 start 视为跨天按 24 小时窗口 */
    private int minutesBetween(LocalTime start, LocalTime end) {
        int minutes =
                end.getHour() * 60 + end.getMinute() - (start.getHour() * 60 + start.getMinute());
        return minutes < 0 ? minutes + 24 * 60 : minutes;
    }

    /** 宽松子串重叠判断：任一方包含另一方即视为冲突（同一概念的不同表述） */
    private boolean overlaps(String left, String right) {
        if (left == null || right == null || left.isBlank() || right.isBlank()) return false;
        String a = left.trim();
        String b = right.trim();
        return a.contains(b) || b.contains(a);
    }
}
