package com.heartpilot.module.agent.requirement;

import com.heartpilot.module.agent.entity.AgentTask;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 结构化需求抽取服务实现。
 *
 * <p>角色定义（轻量串行多 Agent 流水线的第 1 个角色）：需求解析 Agent。
 * 只负责"理解自然语言 → 结构化约束"，不检索、不生成方案；
 * 硬性可行性判断交给 {@link RequirementValidator}（代码），
 * 检索挑选交给候选挑选 Agent，方案编排交给富化器（审计 Agent）。
 *
 * <p>抽取 Prompt 要点：
 * <ul>
 *   <li>固定 JSON Schema 输出（结构化输出），不做自然语言解释</li>
 *   <li>四类约束严格分类：硬性 / 优先 / 可选 / 排除，禁止把排除项塞进偏好</li>
 *   <li>从"目标 + 结构化参数 + 背景"合并理解，但参数优先级高于目标文本</li>
 *   <li>不编造具体店名/商品名，只提取概念与约束</li>
 * </ul>
 */
@Service
public class RequirementExtractionServiceImpl implements RequirementExtractionService {

    /** 最大重试次数：抽取结果非法时携带反馈回传重试，超过后走规则降级 */
    private static final int MAX_RETRIES = 2;

    /** 需求解析 Agent 的固定系统提示词 */
    private static final String SYSTEM_PROMPT =
            """
            你是需求解析 Agent，只做一件事：把用户对"地点见面 / 送礼"的原始需求转换为固定 Schema 的结构化 JSON。
            你不检索、不生成方案、不做可行性判断；可行性由代码校验器负责，你只保证"理解正确、归类正确、信息不丢失"。
            必须遵守：
            1. 只输出符合给定 Schema 的 JSON，不输出任何解释、前后缀或自然语言。
            2. 四类约束严格区分：
               - hardConstraints（硬性约束）：必须满足，如"周六下午""晚上8点前回家""预算不超过500"；
               - priorityPreferences（优先偏好）：尽量满足，如"喜欢广西菜""安静""想散步"；
               - optionalEnhancements（可选加分项）：有余力再做，如"下雨的室内备选""拍照好看"；
               - exclusions（排除黑名单）：坚决不做，如"不要去太吵的商场""不送香水"。否定句（不要/别/避免/排除/不推荐）一律归入 exclusions。
            3. 提取业务实体：
               - 地点（place）：city 城市、startPoint 起点、startTime 开始时间（HH:mm）、latestReturnTime 最晚返程时间（HH:mm）、
                 transportMode 出行方式、partySize 人数、mustVisit 必去点位、recommendedVisit 推荐点位、forbiddenPlaces 禁止点位、
                 stayMinutesPerPlace 每点停留分钟、budgetMin/budgetMax 预算上下限（元）、budgetText 预算原文。
               - 送礼（gift）：recipient 送礼对象、recipientAge 年龄、occasion 场合、budgetMin/budgetMax 预算区间（元）、
                 budgetText 预算原文、stylePreferences 风格/喜好、forbiddenCategories 禁止品类。
            4. 具体店名、品牌、商品名：用户明确提到的原样保留进对应列表；用户没提到的绝不编造。
            5. 结构化参数（地点/预算/问题/背景/行动类型）优先级高于目标正文；目标正文只在参数未覆盖时补充约束。
            6. 时间统一转为 HH:mm 24 小时制；无法确定的时间段留空字符串，不要臆造。
            7. 中文输出；列表为空时输出空数组 []，不要省略字段。
            """;

    private final ChatClient client;
    private final boolean enabled;

    public RequirementExtractionServiceImpl(
            @Qualifier("dashscopeChatModel") ChatModel model,
            @Value("${spring.ai.dashscope.api-key:}") String apiKey) {
        this.client = ChatClient.builder(model).build();
        this.enabled = apiKey != null && !apiKey.isBlank() && !"not-configured".equals(apiKey);
    }

    @Override
    public StructuredRequirement extract(
            AgentTask task, Map<String, Object> parameters, StructuredRequirement prior) {
        if (!enabled) return fallback(task, parameters, prior);
        String feedback = "";
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                ExtractionModel model =
                        client.prompt()
                                .system(SYSTEM_PROMPT)
                                .user(userPrompt(task, parameters, prior, feedback))
                                .call()
                                .entity(ExtractionModel.class);
                if (model == null) {
                    feedback = "上次输出为空，请按 Schema 输出结构化 JSON。";
                    continue;
                }
                StructuredRequirement requirement = normalize(model);
                if (invalid(requirement)) {
                    feedback = "上次输出缺少关键字段（type 或对应实体为空），请补齐完整 Schema。";
                    continue;
                }
                return requirement;
            } catch (Exception ignored) {
                feedback = "上次输出不是合法 JSON，请严格按 Schema 输出。";
            }
        }
        // 重试耗尽：规则降级，保证流程不被 AI 故障阻断
        return fallback(task, parameters, prior);
    }

    /** 组装用户 Prompt：目标 + 结构化参数 + 已确认 prior + 重试反馈 */
    private String userPrompt(
            AgentTask task, Map<String, Object> parameters, StructuredRequirement prior, String feedback) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("计划目标：").append(task.getObjective() == null ? "" : task.getObjective()).append("\n");
        appendParam(prompt, "地点范围", String.valueOf(parameters.getOrDefault("city", "")));
        appendParam(prompt, "预算", String.valueOf(parameters.getOrDefault("budget", "")));
        appendParam(prompt, "行动类型", String.valueOf(parameters.getOrDefault("preferredActionKinds", "")));
        List<?> questions = asList(parameters.get("questions"));
        if (!questions.isEmpty()) {
            prompt.append("需要逐项回答的问题：\n");
            int index = 1;
            for (Object question : questions) {
                prompt.append(index++).append(". ").append(question).append("\n");
            }
        }
        List<?> revisions = asList(parameters.get("revisions"));
        if (!revisions.isEmpty()) {
            prompt.append("历次修改要求：").append(String.join("；", revisions.stream().map(String::valueOf).toList())).append("\n");
        }
        appendParam(prompt, "背景补充（含时间/边界/礼物细节）", String.valueOf(parameters.getOrDefault("contextNotes", "")));
        if (prior != null) {
            prompt.append("此前已确认的结构化需求（在此基础上有改动才更新，未改动的字段原样保留）：\n")
                    .append(priorSummary(prior))
                    .append("\n");
        }
        if (feedback != null && !feedback.isBlank()) {
            prompt.append("\n上次输出被代码校验拒绝：").append(feedback).append("\n");
        }
        prompt.append("\n请输出结构化需求 JSON。");
        return prompt.toString();
    }

    private void appendParam(StringBuilder prompt, String label, String value) {
        if (value == null || value.isBlank() || "null".equalsIgnoreCase(value)) return;
        prompt.append(label).append("：").append(value.trim()).append("\n");
    }

    /** 把 prior 压缩成 Prompt 可读的摘要（不丢字段） */
    private String priorSummary(StructuredRequirement prior) {
        StringBuilder out = new StringBuilder();
        out.append("type=").append(prior.type());
        out.append(";hardConstraints=").append(prior.hardConstraints());
        out.append(";priorityPreferences=").append(prior.priorityPreferences());
        out.append(";optionalEnhancements=").append(prior.optionalEnhancements());
        out.append(";exclusions=").append(prior.exclusions());
        if (prior.place() != null) out.append(";place=").append(prior.place());
        if (prior.gift() != null) out.append(";gift=").append(prior.gift());
        return out.toString();
    }

    /** 模型原始输出 → 规范化后的 StructuredRequirement */
    private StructuredRequirement normalize(ExtractionModel model) {
        RequirementType type = parseType(model.actionKind());
        if (type == null) type = parseType(model.type());
        PlaceRequirement place = null;
        GiftRequirement gift = null;
        if (model.place() != null) {
            PlaceModel p = model.place();
            place = new PlaceRequirement(
                    blankTo(p.city()),
                    blankTo(p.startPoint()),
                    blankTo(p.startTime()),
                    blankTo(p.latestReturnTime()),
                    blankTo(p.transportMode()),
                    p.partySize(),
                    strings(p.mustVisit()),
                    strings(p.recommendedVisit()),
                    strings(p.forbiddenPlaces()),
                    p.stayMinutesPerPlace(),
                    decimal(p.budgetMin()),
                    decimal(p.budgetMax()),
                    blankTo(p.budgetText()));
        }
        if (model.gift() != null) {
            GiftModel g = model.gift();
            gift = new GiftRequirement(
                    blankTo(g.recipient()),
                    g.recipientAge(),
                    blankTo(g.occasion()),
                    decimal(g.budgetMin()),
                    decimal(g.budgetMax()),
                    blankTo(g.budgetText()),
                    strings(g.stylePreferences()),
                    strings(g.forbiddenCategories()));
        }
        return new StructuredRequirement(
                type,
                strings(model.hardConstraints()),
                strings(model.priorityPreferences()),
                strings(model.optionalEnhancements()),
                strings(model.exclusions()),
                place,
                gift,
                true);
    }

    /** 兼容 actionKind（PLACE_VISIT/GIFT_RITUAL）与 type（PLACE/GIFT）两种取值 */
    private RequirementType parseType(String value) {
        if (value == null || value.isBlank()) return null;
        RequirementType fromKind = RequirementType.fromExecutionKind(value.trim());
        if (fromKind != null) return fromKind;
        try {
            return RequirementType.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    /** 结构完整性检查：类型缺失或对应实体缺失视为非法输出 */
    private boolean invalid(StructuredRequirement requirement) {
        if (requirement == null || requirement.type() == null) return true;
        return switch (requirement.type()) {
            case PLACE -> requirement.place() == null;
            case GIFT -> requirement.gift() == null;
        };
    }

    /**
     * 规则降级抽取：不依赖大模型，从已填写的结构化参数拼装 StructuredRequirement。
     * 保证无 Key / 模型故障时流程仍可运行（aiGenerated=false 标记降级）。
     */
    private StructuredRequirement fallback(
            AgentTask task, Map<String, Object> parameters, StructuredRequirement prior) {
        RequirementType type =
                RequirementType.fromExecutionKind(
                        String.valueOf(parameters.getOrDefault("preferredActionKinds", "")));
        if (type == null) {
            // 无法判定类型时尝试从目标文本猜：含地点类词 → PLACE，含送/礼词 → GIFT
            String objective = task.getObjective() == null ? "" : task.getObjective();
            type = objective.matches(".*(送|礼|礼物|生日|纪念日).*") ? RequirementType.GIFT : RequirementType.PLACE;
        }
        Map<String, String> notes = parseContextNotes(String.valueOf(parameters.getOrDefault("contextNotes", "")));
        List<String> hard = new ArrayList<>();
        List<String> priority = new ArrayList<>();
        List<String> optional = new ArrayList<>();
        List<String> exclusions = new ArrayList<>();
        String boundary = notes.getOrDefault("边界", "");
        if (!boundary.isBlank()) {
            for (String part : splitLines(boundary)) {
                if (part.matches("^(?:不|不要|别|避免|排除|拒绝|不能|不推荐).*")) exclusions.add(part);
                else hard.add(part);
            }
        }
        String time = notes.getOrDefault("时间约束", "");
        if (!time.isBlank()) hard.add(time);
        if (task.getObjective() != null && !task.getObjective().isBlank()) priority.add(task.getObjective());
        asList(parameters.get("questions")).forEach(q -> priority.add(String.valueOf(q)));

        if (type == RequirementType.PLACE) {
            BigDecimal max = decimal(String.valueOf(parameters.getOrDefault("budget", "")));
            PlaceRequirement place = new PlaceRequirement(
                    String.valueOf(parameters.getOrDefault("city", "")),
                    "",
                    "",
                    "",
                    "",
                    intOrNull(notes.get("人数")),
                    List.of(),
                    List.of(),
                    List.of(),
                    null,
                    null,
                    max,
                    max == null ? "" : max.toPlainString() + "元");
            return new StructuredRequirement(type, hard, priority, optional, exclusions, place, null, false);
        }
        GiftRequirement gift = new GiftRequirement(
                extractRecipient(task.getObjective(), notes),
                null,
                notes.get("场合"),
                null,
                null,
                notes.getOrDefault("礼物预算", ""),
                extractPreferences(notes, task.getObjective()),
                List.of());
        return new StructuredRequirement(type, hard, priority, optional, exclusions, null, gift, false);
    }

    /** 解析"标签：值"形式的背景补充文本（前端按行拼接） */
    private Map<String, String> parseContextNotes(String contextNotes) {
        Map<String, String> notes = new LinkedHashMap<>();
        if (contextNotes == null || contextNotes.isBlank()) return notes;
        for (String line : contextNotes.split("[\\r\\n]+")) {
            String value = line.trim();
            if (value.isBlank()) continue;
            if (value.matches("^[^：:]{1,8}[：:].+")) {
                int split = Math.max(value.indexOf('：'), value.indexOf(':'));
                String label = value.substring(0, split).trim();
                notes.putIfAbsent(label, value.substring(split + 1).trim());
            }
        }
        return notes;
    }

    private List<String> splitLines(String value) {
        List<String> result = new ArrayList<>();
        if (value == null || value.isBlank()) return result;
        for (String part : value.split("[，,；;。！？!?\\n]+")) {
            String trimmed = part.trim();
            if (!trimmed.isBlank()) result.add(trimmed);
        }
        return result;
    }

    private String extractRecipient(String objective, Map<String, String> notes) {
        if (notes.containsKey("对方喜好")) {
            String raw = notes.get("对方喜好");
            if (raw.matches(".*(女朋友|男朋友|老婆|老公|家人|妈妈|爸爸|朋友|闺蜜|兄弟|对象).*")) {
                return raw.replaceFirst("喜欢.*|喜好.*|，.*|:.*", "").trim();
            }
        }
        if (objective == null) return "";
        for (String keyword : List.of("女朋友", "男朋友", "老婆", "老公", "妈妈", "爸爸", "家人", "朋友", "闺蜜", "兄弟", "对象")) {
            if (objective.contains(keyword)) {
                int index = objective.indexOf(keyword);
                int end = Math.min(objective.length(), index + keyword.length());
                return objective.substring(index, end);
            }
        }
        return "";
    }

    private List<String> extractPreferences(Map<String, String> notes, String objective) {
        List<String> preferences = new ArrayList<>();
        String raw = notes.getOrDefault("对方喜好", "");
        if (!raw.isBlank()) {
            for (String part : raw.split("[，,；;]+")) {
                String trimmed = part.trim().replaceFirst("^(喜欢|偏好|爱好|爱|爱喝|爱玩|爱用)", "");
                if (!trimmed.isBlank()) preferences.add(trimmed);
            }
        }
        if (preferences.isEmpty() && objective != null && objective.contains("喜欢")) {
            int index = objective.indexOf("喜欢");
            String tail = objective.substring(Math.min(objective.length(), index + 2)).trim();
            if (!tail.isBlank()) preferences.add(tail.replaceFirst("[，,。；;].*$", ""));
        }
        return preferences;
    }

    // ---------------- 模型结构化输出 Schema ----------------

    /** 大模型输出的顶层结构化需求 */
    public record ExtractionModel(
            String type,
            String actionKind,
            List<String> hardConstraints,
            List<String> priorityPreferences,
            List<String> optionalEnhancements,
            List<String> exclusions,
            PlaceModel place,
            GiftModel gift) {}

    public record PlaceModel(
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
            String budgetMin,
            String budgetMax,
            String budgetText) {}

    public record GiftModel(
            String recipient,
            Integer recipientAge,
            String occasion,
            String budgetMin,
            String budgetMax,
            String budgetText,
            List<String> stylePreferences,
            List<String> forbiddenCategories) {}

    // ---------------- 小工具 ----------------

    private static List<String> strings(List<String> values) {
        if (values == null) return List.of();
        return values.stream().filter(v -> v != null && !v.isBlank()).map(String::trim).toList();
    }

    private static String blankTo(String value) {
        return value == null ? "" : value.trim();
    }

    private static BigDecimal decimal(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static Integer intOrNull(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            int parsed = Integer.parseInt(value.replaceAll("\\D", ""));
            return parsed <= 0 ? null : parsed;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static List<?> asList(Object raw) {
        if (raw == null) return List.of();
        if (raw instanceof List<?> list) return list;
        return List.of(raw);
    }
}
