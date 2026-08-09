package com.heartpilot.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.heartpilot.domain.AgentTask;
import com.heartpilot.domain.RelationshipProfile;
import com.heartpilot.repository.ProfileRepository;
import com.heartpilot.web.ApiException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** Normalizes task input and composes the user-facing plan preview. */
@Service
public class AgentTaskInputService {
    private static final List<String> KNOWN_CITIES =
            List.of(
                    "北京", "上海", "天津", "重庆", "广州", "深圳", "南宁", "柳州", "桂林", "成都", "杭州", "南京", "武汉",
                    "长沙", "西安", "郑州", "济南", "青岛", "大连", "沈阳", "长春", "哈尔滨", "昆明", "贵阳", "海口", "三亚",
                    "福州", "厦门", "南昌", "合肥", "苏州", "无锡", "宁波", "温州", "石家庄", "太原", "兰州", "西宁", "银川",
                    "乌鲁木齐", "呼和浩特", "拉萨", "香港", "澳门");

    private final ProfileRepository profiles;
    private final ObjectMapper json;

    public AgentTaskInputService(ProfileRepository profiles, ObjectMapper json) {
        this.profiles = profiles;
        this.json = json;
    }

    public String buildPreview(
            AgentTask task,
            String city,
            String budget,
            String places,
            List<String> questions,
            List<String> revisions) {
        return """
                ## 当前候选计划

                ### 目标
                %s

                ### 地点范围
                - 城市：%s
                - 预算上限：%s
                %s

                ### 需要逐项解决的问题
                %s

                ### 按问题分类的检索结果
                %s

                ### 下一步
                请核对上面的分类检索结果是否覆盖你的问题。确认后，我会只围绕这些目标生成完整行动报告；
                不相关的类别不会进入最终报告，未查到可靠资料的部分也不会用其他类别强行代替。
                """
                .formatted(
                        task.getObjective(),
                        city,
                        budgetLabel(budget),
                        revisions.isEmpty() ? "" : "- 累计修改要求：" + String.join("；", revisions),
                        questions.isEmpty()
                                ? "- 暂无单独问题，可在下方继续补充"
                                : "- " + String.join("\n- ", questions),
                        shorten(places, 5_000));
    }

    public String combinedRequirements(AgentTask task, Map<String, Object> parameters) {
        List<String> parts = new ArrayList<>();
        String city = resolveCity(parameters, task.getObjective());
        String budget = parameterText(parameters.get("budget"), "");
        parts.add("当前有效参数（最高优先级）：地点=" + city + "，预算=" + (budget.isBlank() ? "未限定" : budget + "元"));
        parts.add("初始目标（仅保留非冲突意图，地点和金额以当前有效参数为准）：" + task.getObjective());
        List<String> questions = asStringList(parameters.get("questions"));
        if (!questions.isEmpty()) parts.add("需要解决的问题：" + String.join("；", questions));
        List<String> revisions = asStringList(parameters.get("revisions"));
        if (!revisions.isEmpty()) parts.add("历次补充要求：" + String.join("；", revisions));
        RelationshipProfile profile = profiles.findByUserId(task.getUserId()).orElse(null);
        if (profile != null) {
            if (profile.getPreferences() != null && !profile.getPreferences().isBlank()) {
                parts.add("关系档案偏好：" + profile.getPreferences().trim());
            }
            if (profile.getBoundaries() != null && !profile.getBoundaries().isBlank()) {
                parts.add("必须遵守的关系边界：" + profile.getBoundaries().trim());
            }
        }
        return String.join("｜", parts);
    }

    public List<String> asStringList(Object raw) {
        if (raw == null) return new ArrayList<>();
        List<?> values = raw instanceof List<?> list ? list : List.of(raw);
        List<String> result = new ArrayList<>();
        for (Object value : values) {
            String text = String.valueOf(value).trim();
            if (!text.isBlank() && !"null".equalsIgnoreCase(text) && !result.contains(text)) {
                result.add(text);
            }
        }
        return result;
    }

    public List<String> mergeQuestions(List<String> existing, List<String> incoming) {
        List<String> merged = new ArrayList<>(existing);
        if (incoming != null) {
            for (String question : incoming) {
                String normalized = question == null ? "" : question.trim();
                if (!normalized.isBlank() && !merged.contains(normalized)) merged.add(normalized);
            }
        }
        return merged;
    }

    public List<String> extractQuestions(String note) {
        if (note == null || note.isBlank()) return List.of();
        List<String> extracted = new ArrayList<>();
        for (String part : note.split("[\\r\\n；;]+")) {
            String value = part.trim().replaceFirst("^[\\-•\\d.、\\s]+", "");
            boolean questionLike =
                    value.endsWith("?")
                            || value.endsWith("？")
                            || value.matches(".*(什么|哪里|哪家|哪个|是否|怎么|如何|能否|可不可以|可以.*吗).*?")
                            || (value.contains("停车") && value.matches(".*(免费|哪里|哪儿|哪家|什么).*"));
            if (questionLike && !value.isBlank() && !extracted.contains(value)) {
                extracted.add(value);
            }
        }
        return extracted;
    }

    public String searchCategories(String searchResult) {
        if (searchResult == null || searchResult.isBlank()) return "按问题动态提取";
        for (String line : searchResult.split("\\R")) {
            if (line.startsWith("动态检索类别：")) return line.substring("动态检索类别：".length()).trim();
        }
        return "按问题动态提取";
    }

    public String parameterText(Object value, String fallback) {
        if (value == null) return fallback;
        String text = String.valueOf(value).trim();
        if (text.isBlank() || "null".equalsIgnoreCase(text)) return fallback;
        try {
            return normalizeBudget(new BigDecimal(text)).toPlainString();
        } catch (NumberFormatException ignored) {
            return text;
        }
    }

    public String budgetLabel(String budget) {
        return "未限定".equals(budget) || budget.isBlank() ? "未限定" : budget + " 元";
    }

    public void normalizeStoredBudget(Map<String, Object> parameters) {
        Object raw = parameters.get("budget");
        if (raw == null || String.valueOf(raw).isBlank()) {
            parameters.remove("budget");
            return;
        }
        try {
            BigDecimal budget = new BigDecimal(String.valueOf(raw).trim());
            if (budget.signum() < 0) throw ApiException.badRequest("预算不能小于 0");
            parameters.put("budget", normalizeBudget(budget));
        } catch (NumberFormatException ignored) {
            throw ApiException.badRequest("预算格式不正确");
        }
    }

    public BigDecimal normalizeBudget(BigDecimal budget) {
        return new BigDecimal(budget.stripTrailingZeros().toPlainString());
    }

    public Map<String, Object> readParameters(AgentTask task) {
        try {
            return new LinkedHashMap<>(
                    json.readValue(task.getParametersJson(), new TypeReference<>() {}));
        } catch (Exception ignored) {
            return new LinkedHashMap<>();
        }
    }

    public String writeParameters(Map<String, Object> parameters) {
        try {
            return json.writeValueAsString(parameters);
        } catch (Exception ignored) {
            return "{}";
        }
    }

    public String resolveCity(Map<String, Object> parameters, String text) {
        String city = String.valueOf(parameters.getOrDefault("city", "")).trim();
        if (!city.isBlank() && !"null".equalsIgnoreCase(city)) return city.replace("市", "");
        return findKnownCity(text);
    }

    public String findKnownCity(String text) {
        if (text == null) return "";
        return KNOWN_CITIES.stream().filter(text::contains).findFirst().orElse("");
    }

    public String normalizeIdempotencyKey(String key) {
        if (key == null || key.isBlank()) return null;
        String normalized = key.trim();
        if (normalized.length() > 96) throw ApiException.badRequest("Idempotency-Key 最长为 96 个字符");
        if (!normalized.matches("[A-Za-z0-9._:-]+")) {
            throw ApiException.badRequest("Idempotency-Key 只能包含字母、数字、点、下划线、冒号和短横线");
        }
        return normalized;
    }

    private String shorten(String value, int length) {
        if (value == null) return "";
        return value.substring(0, Math.min(length, value.length()));
    }
}
