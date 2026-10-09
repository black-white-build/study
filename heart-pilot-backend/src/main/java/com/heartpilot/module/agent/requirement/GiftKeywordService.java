package com.heartpilot.module.agent.requirement;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
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
 * 送礼关键词池服务：候选池前置 + 黑名单过滤。
 *
 * <p>角色定义（轻量串行多 Agent 流水线的第 2 个角色）：商品检索 Agent。 基于结构化约束生成多组差异化搜索词（3 套方案强制分属不同品类）， 随后由纯 Java 代码做两层把关：
 *
 * <ol>
 *   <li>黑名单过滤：命中排除/禁止品类的关键词直接删除，并抽取排除词附加到搜索链接（减号过滤）
 *   <li>平台 URL 生成：生成淘宝 / 京东 / 抖音的搜索跳转链接
 * </ol>
 */
@Service
public class GiftKeywordService {
    /** 关键词组数（3 套方案强制不同品类） */
    private static final int GROUP_COUNT = 3;

    /** 每组关键词数量 */
    private static final int KEYWORDS_PER_GROUP = 2;

    /** 关键词结果非法时最大重试次数 */
    private static final int MAX_RETRIES = 2;

    /** 商品检索 Agent 固定系统提示词 */
    private static final String SYSTEM_PROMPT =
            """
            你是商品检索 Agent。任务：根据用户的送礼结构化需求，生成 3 组差异化搜索关键词，供电商平台搜索跳转使用。
            必须遵守：
            1. 输出 groups 数组，恰好 3 组；每组 { "category": 品类名, "keywords": ["关键词1", "关键词2"] }。
            2. 3 组的 category 必须属于不同品类，禁止重复（如：茶具 / 游戏外设 / 香氛）。
            3. keywords 要具体可搜：包含品类+场景/风格修饰（如"明前龙井 礼盒""客制化机械键盘"），不要空泛。
            4. 严格遵守预算区间与风格偏好；命中禁止品类的词绝不出现（如禁香水就不给香水/香氛）。
            5. 关键词里不要带符号；中文优先，适当加入英文品牌词。
            6. 只输出 JSON，不解释。
            """;

    private final ChatClient client;
    private final boolean enabled;

    /** 轻量领域提示（Tier3 RAG 简化版：礼品优缺点词库） */
    private final DomainTipsProvider tipsProvider;

    public GiftKeywordService(
            @Qualifier("dashscopeChatModel") ChatModel model,
            @Value("${spring.ai.dashscope.api-key:}") String apiKey,
            DomainTipsProvider tipsProvider) {
        this.client = ChatClient.builder(model).build();
        this.enabled = apiKey != null && !apiKey.isBlank() && !"not-configured".equals(apiKey);
        this.tipsProvider = tipsProvider;
    }

    /** 平台跳转链接 */
    public record PlatformUrl(String platform, String url) {}

    /** 一组关键词 */
    public record KeywordGroup(String category, List<String> keywords) {}

    /** 关键词池方案：分组 + 每个关键词的平台链接 + 排除词应用情况 */
    public record GiftKeywordPlan(
            List<KeywordGroup> groups,
            Map<String, List<PlatformUrl>> urlsByKeyword,
            List<String> excludedTerms,
            boolean aiGenerated) {}

    /**
     * 生成并过滤关键词池。
     *
     * @param requirement 结构化需求（GIFT 类型）
     * @param fallbackSeed 降级用种子文本（目标/背景）
     * @return 关键词方案
     */
    public GiftKeywordPlan build(StructuredRequirement requirement, String fallbackSeed) {
        GiftRequirement gift = requirement == null ? null : requirement.gift();
        List<String> forbidden =
                gift == null
                        ? requirement == null ? List.of() : requirement.exclusions()
                        : merge(gift.forbiddenCategories(), requirement.exclusions());
        List<KeywordGroup> groups = new ArrayList<>();
        if (!enabled) {
            groups = fallbackGroups(fallbackSeed);
        } else {
            String feedback = "";
            for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
                try {
                    KeywordModel model =
                            client.prompt()
                                    .system(SYSTEM_PROMPT)
                                    .user(userPrompt(requirement, fallbackSeed, feedback))
                                    .call()
                                    .entity(KeywordModel.class);
                    List<KeywordGroup> raw = normalizeGroups(model);
                    String violation = validateGroups(raw, forbidden);
                    if (violation != null) {
                        feedback = "上次生成被代码校验拒绝：" + violation + "。请重新生成。";
                        continue;
                    }
                    groups = raw;
                    break;
                } catch (Exception ignored) {
                    feedback = "上次输出不是合法 JSON，请严格按 Schema 输出 groups。";
                }
            }
            if (groups.isEmpty()) groups = fallbackGroups(fallbackSeed);
        }
        // 代码层黑名单过滤：命中排除词的关键词直接删除；空组补齐兜底
        List<String> excludedTerms = new ArrayList<>();
        List<KeywordGroup> filtered = new ArrayList<>();
        for (KeywordGroup group : groups) {
            List<String> kept = new ArrayList<>();
            for (String keyword : group.keywords()) {
                String hit = hitForbidden(keyword, forbidden);
                if (hit == null) {
                    kept.add(keyword);
                } else if (!excludedTerms.contains(hit)) {
                    excludedTerms.add(hit);
                }
            }
            if (!kept.isEmpty()) filtered.add(new KeywordGroup(group.category(), kept));
        }
        if (filtered.isEmpty()) {
            filtered = fallbackGroups(fallbackSeed);
            excludedTerms.clear();
        }
        // 生成多平台搜索跳转链接
        Map<String, List<PlatformUrl>> urls = new java.util.LinkedHashMap<>();
        for (KeywordGroup group : filtered) {
            for (String keyword : group.keywords()) {
                urls.put(keyword, platformUrls(keyword, excludedTerms));
            }
        }
        return new GiftKeywordPlan(filtered, urls, excludedTerms, !groups.isEmpty() && enabled);
    }

    /** 组装生成 Prompt */
    private String userPrompt(
            StructuredRequirement requirement, String fallbackSeed, String feedback) {
        StringBuilder prompt = new StringBuilder();
        if (requirement != null) {
            prompt.append("结构化需求（约束的唯一来源）：\n").append(requirement.summary()).append("\n");
        } else {
            prompt.append("背景描述：").append(fallbackSeed).append("\n");
        }
        // 轻量领域提示（礼品优缺点、适用场景），减少编造内容
        List<String> tips = tipsProvider.tipsFor(requirement);
        if (!tips.isEmpty()) {
            prompt.append("领域提示（生成关键词时参考）：\n");
            for (String tip : tips) prompt.append("- ").append(tip).append("\n");
            prompt.append("\n");
        }
        if (feedback != null && !feedback.isBlank()) {
            prompt.append("\n上次生成被拒绝：").append(feedback).append("\n");
        }
        prompt.append("\n请输出 groups JSON（3 组不同品类）。");
        return prompt.toString();
    }

    /** 模型输出 → 关键词分组（去空） */
    private List<KeywordGroup> normalizeGroups(KeywordModel model) {
        if (model == null || model.groups() == null) return List.of();
        List<KeywordGroup> result = new ArrayList<>();
        for (GroupModel raw : model.groups()) {
            if (raw == null || raw.category() == null || raw.category().isBlank()) continue;
            List<String> keywords = new ArrayList<>();
            if (raw.keywords() != null) {
                for (String keyword : raw.keywords()) {
                    if (keyword != null && !keyword.isBlank()) keywords.add(keyword.trim());
                }
            }
            if (!keywords.isEmpty()) result.add(new KeywordGroup(raw.category().trim(), keywords));
        }
        return result;
    }

    /** 代码校验：组数 ≥1、品类去重、关键词不命中黑名单、每组词数达标；返回违规说明或 null */
    private String validateGroups(List<KeywordGroup> groups, List<String> forbidden) {
        if (groups.isEmpty()) return "groups 为空";
        LinkedHashSet<String> categories = new LinkedHashSet<>();
        for (KeywordGroup group : groups) {
            if (!categories.add(group.category().toLowerCase())) {
                return "品类重复：「" + group.category() + "」出现多次，3 组必须不同品类";
            }
            if (group.keywords().size() < KEYWORDS_PER_GROUP) {
                return "品类「" + group.category() + "」关键词不足 " + KEYWORDS_PER_GROUP + " 个";
            }
            for (String keyword : group.keywords()) {
                if (hitForbidden(keyword, forbidden) != null) {
                    return "关键词「" + keyword + "」命中禁止品类，违反黑名单约束";
                }
            }
        }
        return null;
    }

    /** 关键词是否命中黑名单：返回命中的排除词，未命中返回 null */
    private String hitForbidden(String keyword, List<String> forbidden) {
        if (keyword == null || forbidden == null) return null;
        for (String term : forbidden) {
            if (term == null || term.isBlank()) continue;
            if (keyword.contains(term) || term.contains(keyword)) return term;
        }
        return null;
    }

    /** 规则降级关键词组：从种子文本抽词，保证无 Key 时也能产出 */
    private List<KeywordGroup> fallbackGroups(String seed) {
        String text = seed == null ? "" : seed;
        List<KeywordGroup> groups = new ArrayList<>();
        if (text.contains("茶")) groups.add(new KeywordGroup("茶饮茶具", List.of("茶叶 礼盒", "茶具 套装")));
        if (text.contains("游戏") || text.contains("外设") || text.contains("电脑"))
            groups.add(new KeywordGroup("游戏外设", List.of("机械键盘", "游戏耳机")));
        if (text.contains("香") || text.contains("香水"))
            groups.add(new KeywordGroup("家居香氛", List.of("香薰蜡烛", "香薰机")));
        if (text.contains("书") || text.contains("阅读"))
            groups.add(new KeywordGroup("阅读文具", List.of("手账本 套装", "钢笔 礼盒")));
        if (groups.isEmpty())
            groups.add(new KeywordGroup("生活好物", List.of("精致 实用 礼物", "高颜值 礼物 推荐")));
        return groups.subList(0, Math.min(GROUP_COUNT, groups.size()));
    }

    /**
     * 生成淘宝/京东/拼多多搜索跳转链接。 平台选择说明：京东、拼多多网页版匿名即可查看搜索结果； 淘宝网页版首次打开需登录（登录一次后浏览器记住 Cookie 不再弹）；
     * 抖音网页版强制登录/验证码，已移除该入口。
     */
    private List<PlatformUrl> platformUrls(String keyword, List<String> excludedTerms) {
        // 电商搜索排除语法（减号过滤）：京东支持，其余平台尽力兼容
        String jdQuery =
                excludedTerms.isEmpty()
                        ? keyword
                        : keyword
                                + excludedTerms.stream()
                                        .map(t -> " -" + t)
                                        .reduce("", String::concat);
        return List.of(
                new PlatformUrl("淘宝", "https://s.taobao.com/search?q=" + encode(keyword)),
                new PlatformUrl("京东", "https://search.jd.com/Search?keyword=" + encode(jdQuery)),
                new PlatformUrl(
                        "拼多多",
                        "https://mobile.yangkeduo.com/search_result.html?search_key="
                                + encode(keyword)));
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private List<String> merge(List<String> left, List<String> right) {
        List<String> result = new ArrayList<>(left);
        for (String value : right) {
            if (value != null && !value.isBlank() && !result.contains(value)) result.add(value);
        }
        return result;
    }

    /** 模型结构化输出：关键词组 */
    public record KeywordModel(List<GroupModel> groups) {}

    public record GroupModel(String category, List<String> keywords) {}
}
