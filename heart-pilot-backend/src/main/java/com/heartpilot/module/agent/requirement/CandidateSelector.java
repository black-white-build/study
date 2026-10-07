package com.heartpilot.module.agent.requirement;

import com.heartpilot.module.agent.service.PlaceSearchService;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 候选点位挑选 Agent（Tier2：候选池前置，参考 ITINERA）。
 *
 * <p>角色定义（轻量串行多 Agent 流水线的第 2 个角色）：工具检索与候选挑选 Agent。
 * 流程：先由高德 API 拉取周边 POI 候选池（带坐标、基础标签），本 Agent 只从候选池里
 * 挑选点位并编排顺序，禁止凭空编造地点——LLM 只做"挑选"，不做"创造"。
 *
 * <p>可靠性设计：
 * <ul>
 *   <li>候选池条目带全局索引，模型输出只能引用池内索引，代码校验索引合法性</li>
 *   <li>挑选结果非法（索引越界/重复/数量超限）→ 携带违规信息回传重试，最多 2 次（Tier3 重试闭环）</li>
 *   <li>模型不可用时规则降级：每个类别取第一个，保证旧行为兼容</li>
 * </ul>
 */
@Service
public class CandidateSelector {
    /** 挑选结果非法时最大重试次数 */
    private static final int MAX_RETRIES = 2;

    /** 候选挑选 Agent 固定系统提示词 */
    private static final String SYSTEM_PROMPT =
            """
            你是候选挑选 Agent。任务：从给定的候选点位池中挑选点位并编排顺序，生成一份符合用户约束的行程。
            只允许从候选池中挑选，绝对禁止编造池外地点。
            必须遵守：
            1. 输出 choices 数组，每个元素是 { "index": 候选池序号, "stayMinutes": 预计停留分钟 }。
            2. index 必须来自候选池给出的序号，不得超出范围，不得编造序号。
            3. 数量限制：不超过用户要求的最大点位数量，至少 1 个。
            4. 严格遵守用户硬性约束（时间窗口、必去点位、排除黑名单）；必去点位若在池中必须选入。
            5. 排除黑名单中的点位绝不选入；优先偏好按匹配度排序。
            6. 时间资源要合理：停留总时长 + 通勤时间不能明显超出时间窗口，超出时优先减少可选点位。
            7. 只输出 JSON，不解释。
            """;

    private final ChatClient client;
    private final boolean enabled;
    /** 轻量领域提示（Tier3 RAG 简化版：规则词库，原型不接 PGVector） */
    private final DomainTipsProvider tipsProvider;

    public CandidateSelector(
            @Qualifier("dashscopeChatModel") ChatModel model,
            @Value("${spring.ai.dashscope.api-key:}") String apiKey,
            DomainTipsProvider tipsProvider) {
        this.client = ChatClient.builder(model).build();
        this.enabled = apiKey != null && !apiKey.isBlank() && !"not-configured".equals(apiKey);
        this.tipsProvider = tipsProvider;
    }

    /** 挑选结果：按序的点位 + 每点停留分钟 + 是否 AI 挑选 */
    public record SelectionResult(
            List<PlaceSearchService.Place> places,
            List<Integer> stayMinutes,
            boolean aiGenerated) {
        public SelectionResult {
            stayMinutes = stayMinutes == null ? List.of() : stayMinutes;
        }
    }

    /**
     * 从候选池挑选并排序点位。
     * @param requirement 结构化需求（硬性/优先/排除约束）
     * @param pool 高德候选池（分组检索结果）
     * @param maxPlaces 最大点位数量
     * @return 挑选结果
     */
    public SelectionResult select(
            StructuredRequirement requirement, PlaceSearchService.SearchResult pool, int maxPlaces) {
        // 展平候选池并建立全局索引
        List<PlaceSearchService.Place> poolPlaces = new ArrayList<>();
        List<String> poolLabels = new ArrayList<>();
        for (PlaceSearchService.SearchGroup group : pool.groups()) {
            for (PlaceSearchService.Place place : group.places()) {
                poolPlaces.add(place);
                poolLabels.add(group.label());
            }
        }
        if (poolPlaces.isEmpty() || maxPlaces <= 0) {
            return new SelectionResult(List.of(), List.of(), false);
        }
        if (!enabled) return ruleBased(pool, maxPlaces);

        String feedback = "";
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                SelectionModel model =
                        client.prompt()
                                .system(SYSTEM_PROMPT)
                                .user(userPrompt(requirement, poolPlaces, poolLabels, maxPlaces, feedback))
                                .call()
                                .entity(SelectionModel.class);
                List<Choice> choices = model == null ? List.of() : model.choices();
                String violation = validateChoices(choices, poolPlaces.size(), maxPlaces);
                if (violation != null) {
                    feedback = "上次挑选被代码校验拒绝：" + violation + "。请重新挑选。";
                    continue;
                }
                List<PlaceSearchService.Place> selected = new ArrayList<>();
                List<Integer> stays = new ArrayList<>();
                for (Choice choice : choices) {
                    PlaceSearchService.Place place = poolPlaces.get(choice.index());
                    if (selected.stream().anyMatch(p -> samePlace(p, place))) continue;
                    selected.add(place);
                    stays.add(choice.stayMinutes() == null || choice.stayMinutes() <= 0 ? 60 : choice.stayMinutes());
                }
                if (!selected.isEmpty()) return new SelectionResult(selected, stays, true);
                feedback = "上次挑选结果为空，请至少挑选 1 个点位。";
            } catch (Exception ignored) {
                feedback = "上次输出不是合法 JSON，请严格按 Schema 输出 choices。";
            }
        }
        return ruleBased(pool, maxPlaces);
    }

    /** 规则降级挑选：每个类别取第一个，类别不足时从全部点位补齐（兼容旧行为） */
    private SelectionResult ruleBased(PlaceSearchService.SearchResult pool, int maxPlaces) {
        Map<String, PlaceSearchService.Place> selected = new java.util.LinkedHashMap<>();
        for (PlaceSearchService.SearchGroup group : pool.groups()) {
            if (group.places().isEmpty()) continue;
            PlaceSearchService.Place first = group.places().getFirst();
            selected.putIfAbsent(placeKey(first), first);
            if (selected.size() >= maxPlaces) break;
        }
        for (PlaceSearchService.Place place : pool.places()) {
            selected.putIfAbsent(placeKey(place), place);
            if (selected.size() >= maxPlaces) break;
        }
        List<PlaceSearchService.Place> places = new ArrayList<>(selected.values());
        return new SelectionResult(
                places, places.stream().map(p -> 60).toList(), false);
    }

    /** 组装挑选 Prompt：结构化约束 + 候选池清单 */
    private String userPrompt(
            StructuredRequirement requirement,
            List<PlaceSearchService.Place> poolPlaces,
            List<String> poolLabels,
            int maxPlaces,
            String feedback) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("结构化需求（约束的唯一来源）：\n").append(requirement.summary()).append("\n\n");
        // 轻量领域常识提示（闭馆时间、避坑等），减少编造内容
        List<String> tips = tipsProvider.tipsFor(requirement);
        if (!tips.isEmpty()) {
            prompt.append("领域提示（挑选时参考）：\n");
            for (String tip : tips) prompt.append("- ").append(tip).append("\n");
            prompt.append("\n");
        }
        prompt.append("候选点位池（只允许从中挑选，最多 ").append(maxPlaces).append(" 个）：\n");
        for (int i = 0; i < poolPlaces.size(); i++) {
            PlaceSearchService.Place place = poolPlaces.get(i);
            prompt.append(i)
                    .append(". ")
                    .append(place.name())
                    .append("｜类别：")
                    .append(poolLabels.get(i))
                    .append("｜地址：")
                    .append(place.address() == null ? "" : place.address())
                    .append("｜类型：")
                    .append(place.type() == null ? "" : place.type());
            if (place.rating() != null && !place.rating().isBlank()) {
                prompt.append("｜评分：").append(place.rating());
            }
            prompt.append("\n");
        }
        if (feedback != null && !feedback.isBlank()) {
            prompt.append("\n上次挑选被拒绝：").append(feedback).append("\n");
        }
        prompt.append("\n请输出 choices JSON。");
        return prompt.toString();
    }

    /** 代码校验挑选结果：索引越界/数量超限/为空/重复，返回违规说明或 null */
    private String validateChoices(List<Choice> choices, int poolSize, int maxPlaces) {
        if (choices == null || choices.isEmpty()) return "choices 为空";
        if (choices.size() > maxPlaces) return "数量超过上限 " + maxPlaces;
        LinkedHashSet<Integer> seen = new LinkedHashSet<>();
        for (Choice choice : choices) {
            if (choice == null || choice.index() == null) return "存在缺少 index 的选择项";
            int index = choice.index();
            if (index < 0 || index >= poolSize) return "index " + index + " 超出候选池范围（0~" + (poolSize - 1) + "）";
            if (!seen.add(index)) return "index " + index + " 重复";
        }
        return null;
    }

    private boolean samePlace(PlaceSearchService.Place left, PlaceSearchService.Place right) {
        if (left.poiId() != null && !left.poiId().isBlank() && left.poiId().equals(right.poiId()))
            return true;
        return placeKey(left).equals(placeKey(right));
    }

    private String placeKey(PlaceSearchService.Place place) {
        return place.poiId().isBlank()
                ? place.name() + "|" + place.address() + "|" + place.location()
                : place.poiId();
    }

    /** 模型结构化输出：一组挑选项 */
    public record SelectionModel(List<Choice> choices) {}

    public record Choice(Integer index, Integer stayMinutes) {}
}
