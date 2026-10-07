package com.heartpilot.module.agent.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.heartpilot.module.agent.entity.PlanActionItem;
import com.heartpilot.module.agent.entity.enums.ExecutionKind;
import com.heartpilot.module.agent.requirement.GiftKeywordService;
import com.heartpilot.module.agent.requirement.RequirementStateService;
import com.heartpilot.module.agent.requirement.StructuredRequirement;
import com.heartpilot.module.agent.service.ActionLanguageService;
import com.heartpilot.module.agent.service.ActionLanguageService.GiftIdea;
import com.heartpilot.module.agent.service.PlanningContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 表达型行动富化器（礼物/仪式）。
 * 候选礼物以"AI 分析用户输入后生成的具体商品方向"为主（GiftPlan.ideas），
 * 不依赖联网搜索 key 就能给出贴合对方喜好的推荐。
 *
 * <p>Tier2 改造（维持合规边界：不爬取电商商品、不接入 CPS）：
 * 基于结构化需求生成差异化关键词池（3 套方案强制不同品类）→ 纯 Java 黑名单过滤
 * （自动把排除项转为电商搜索排除语法）→ 批量生成淘宝 / 京东 / 抖音搜索跳转链接。
 * 只生成跳转链接，不获取、不渲染商品图片与价格；页面标注与电商平台无关联。
 */
@Service
public class GiftRitualActionEnricher extends AbstractActionEnricher {
    /** 展示的最大候选条数 */
    private static final int MAX_IDEAS = 8;

    private final ActionLanguageService language;
    private final RequirementStateService requirementState;
    private final GiftKeywordService keywordService;

    public GiftRitualActionEnricher(
            ActionLanguageService language,
            RequirementStateService requirementState,
            GiftKeywordService keywordService,
            ObjectMapper json) {
        super(json);
        this.language = language;
        this.requirementState = requirementState;
        this.keywordService = keywordService;
    }

    @Override
    public boolean supports(ExecutionKind kind) {
        return kind == ExecutionKind.GIFT_RITUAL;
    }

    @Override
    public EnrichedAction enrich(ActionDraft draft, PlanningContext context) {
        PlanActionItem item = baseItem(draft, context);
        // 结构化需求（步骤 1 已持久化，是黑名单过滤与关键词生成的约束来源）
        StructuredRequirement requirement = null;
        RequirementStateService.RequirementSnapshot snapshot = requirementState.get(context.task().getId());
        if (snapshot != null && snapshot.requirement() != null) {
            requirement = snapshot.requirement();
        }
        ActionLanguageService.GiftPlan gift =
                language.draftGift(goalText(draft, context), context.task().getObjective(), context.budget());

        // Tier2：关键词池前置 + 黑名单过滤 + 多平台跳转链接
        GiftKeywordService.GiftKeywordPlan keywordPlan =
                keywordService.build(
                        requirement,
                        (context.task().getObjective() == null ? "" : context.task().getObjective())
                                + "｜"
                                + (gift.preparation() == null ? "" : gift.preparation()));
        List<Map<String, Object>> giftIdeas =
                attachKeywordUrls(gift.ideas(), keywordPlan);
        boolean linked = giftIdeas.stream().anyMatch(i -> !((List<?>) i.getOrDefault("urls", List.of())).isEmpty());

        // instruction 只写引导性内容：准备思路 + AI 候选方向；
        // 准备事项/预算安排/执行步骤由 payload 结构化渲染，不再重复拼进正文
        StringBuilder instruction = new StringBuilder();
        instruction.append("准备思路：").append(gift.preparation());
        if (!giftIdeas.isEmpty()) {
            instruction.append("\n根据你的需求分析，候选礼物方向：");
            for (Map<String, Object> idea : giftIdeas) {
                instruction.append("\n- ").append(idea.get("title"));
            }
            instruction.append(linked
                    ? "\n已为你生成多平台搜索链接（淘宝 / 京东 / 抖音），点击直达搜索结果页自行挑选；价格以实际为准，与平台无关联。"
                    : "\n（搜索链接暂不可用，以上为 AI 基于你输入的喜好与预算推荐，价格以实际为准）");
        }
        item.setInstruction(instruction.toString());
        item.setTimingSuggestion("选一个对方没有压力、值得被记得的日子");

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("preparation", gift.preparation());
        data.put("budgetText", gift.budgetText());
        data.put("steps", gift.steps());
        data.put("giftIdeas", giftIdeas);
        data.put("searchStatus", linked ? "KEYWORD_URLS" : "AI_ONLY");
        data.put("excludedTerms", keywordPlan.excludedTerms());
        data.put("disclaimer", "本页仅提供平台搜索跳转链接，不抓取、不展示商品图片与价格，与淘宝/京东/抖音无任何关联。");
        item.setPayloadJson(payload(data));

        List<String> refs = new ArrayList<>();
        giftIdeas.forEach(
                idea -> {
                    Object raw = idea.get("urls");
                    if (raw instanceof List<?> urls) {
                        for (Object url : urls) {
                            if (url instanceof Map<?, ?> entry && entry.get("url") != null) {
                                refs.add(String.valueOf(entry.get("url")));
                            }
                        }
                    }
                });
        item.setSourceReferencesJson(refs(refs));
        return new EnrichedAction(item, refs);
    }

    /**
     * 把 AI 候选与关键词池分组配对：每个候选附品类、搜索关键词与多平台跳转链接。
     * 候选数超过分组数时循环复用第一组关键词，保证每个候选都有可点击的搜索入口。
     */
    private List<Map<String, Object>> attachKeywordUrls(
            List<GiftIdea> aiIdeas, GiftKeywordService.GiftKeywordPlan keywordPlan) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (aiIdeas == null) return result;
        List<GiftKeywordService.KeywordGroup> groups = keywordPlan.groups();
        int index = 0;
        for (GiftIdea idea : aiIdeas) {
            if (idea == null || idea.title() == null || idea.title().isBlank()) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("title", idea.title().trim());
            row.put("reason", idea.reason() == null ? "" : idea.reason().trim());
            row.put("priceHint", idea.priceHint() == null ? "" : idea.priceHint().trim());
            if (!groups.isEmpty()) {
                GiftKeywordService.KeywordGroup group = groups.get(index % groups.size());
                row.put("category", group.category());
                row.put("keywords", group.keywords());
                String head = group.keywords().getFirst();
                List<GiftKeywordService.PlatformUrl> urls =
                        keywordPlan.urlsByKeyword().getOrDefault(head, List.of());
                row.put("urls", urls);
            } else {
                row.put("urls", List.of());
            }
            result.add(row);
            index++;
            if (result.size() >= MAX_IDEAS) break;
        }
        return result;
    }

    private String goalText(ActionDraft draft, PlanningContext context) {
        String goal = draft.goalType() == null ? "" : draft.goalType().label();
        return goal.isBlank() ? context.task().getObjective() : goal + "：" + context.task().getObjective();
    }
}
