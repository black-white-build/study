package com.heartpilot.module.agent.requirement;

import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import org.springframework.stereotype.Service;

/**
 * 行程时间资源校验器（纯 Java，不依赖大模型）。
 *
 * <p>在候选点位选定、路线算出之后，用代码计算 「通勤耗时 + 点位停留总时长」并对比最晚返程时间，判断行程是否可行。 与 Tier1 需求校验器互补：需求校验器在生成前用估算值拦截明显冲突，
 * 本校验器在生成后用真实路线耗时复核，输出可量化结论。
 */
@Service
public class ItineraryFeasibilityCalculator {

    /** 计算结果：总需时长、窗口时长、是否可行、说明文本 */
    public record Feasibility(
            int requiredMinutes, int windowMinutes, boolean feasible, String message) {}

    /**
     * 计算行程可行性。
     *
     * @param startTime 开始时间（HH:mm，可空）
     * @param latestReturnTime 最晚返程时间（HH:mm，可空）
     * @param commuteMinutes 各段通勤耗时（分钟）
     * @param stayMinutes 各点位停留耗时（分钟，与点位一一对应）
     * @param bufferMinutes 额外缓冲（默认 15）
     * @return 可行性结论
     */
    public Feasibility calculate(
            String startTime,
            String latestReturnTime,
            java.util.List<Integer> commuteMinutes,
            java.util.List<Integer> stayMinutes,
            int bufferMinutes) {
        LocalTime start = parse(startTime);
        LocalTime end = parse(latestReturnTime);
        int commute = sum(commuteMinutes);
        int stay = sum(stayMinutes);
        int required = commute + stay + Math.max(0, bufferMinutes);
        if (start == null || end == null) {
            return new Feasibility(
                    required,
                    -1,
                    true,
                    "未提供完整时间窗口，行程耗时约 "
                            + required
                            + " 分钟（通勤 "
                            + commute
                            + " + 停留 "
                            + stay
                            + "），请按此核对");
        }
        int window = minutesBetween(start, end);
        boolean feasible = required <= window;
        String message =
                feasible
                        ? "时间可行：预计行程约 "
                                + required
                                + " 分钟（通勤 "
                                + commute
                                + " + 停留 "
                                + stay
                                + " + 缓冲 "
                                + bufferMinutes
                                + "），时间窗口 "
                                + window
                                + " 分钟，余量 "
                                + (window - required)
                                + " 分钟。"
                        : "时间紧张：预计行程约 "
                                + required
                                + " 分钟（通勤 "
                                + commute
                                + " + 停留 "
                                + stay
                                + " + 缓冲 "
                                + bufferMinutes
                                + "），但时间窗口只有 "
                                + window
                                + " 分钟，超出约 "
                                + (required - window)
                                + " 分钟；建议删减 1-2 个点位或放宽返程时间。";
        return new Feasibility(required, window, feasible, message);
    }

    private LocalTime parse(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return LocalTime.parse(value.trim().replace('：', ':'));
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private int minutesBetween(LocalTime start, LocalTime end) {
        int minutes =
                end.getHour() * 60 + end.getMinute() - (start.getHour() * 60 + start.getMinute());
        return minutes < 0 ? minutes + 24 * 60 : minutes;
    }

    private int sum(java.util.List<Integer> values) {
        int total = 0;
        if (values != null) {
            for (Integer value : values) if (value != null) total += Math.max(0, value);
        }
        return total;
    }
}
