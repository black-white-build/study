package com.heartpilot.module.agent.service.impl;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * 用户口语意图 → 高德 POI 分类的中央映射表。
 * Central mapping between user language and AMap POI categories.
 *
 * 每个枚举值描述一类本地生活意图，携带四组数据：
 * - userAliases：用户可能说的口语词（用于把用户意图归类到本类别）
 * - amapTypeKeywords：高德 POI 名称/类型文本里应包含的关键词
 * - amapTypeCodePrefixes：高德 POI 类型码前缀（如 "05"=餐饮），请求时补成完整 types
 * - searchKeywords：用于网页搜索的补充关键词
 * 另有内部枚举 SpecificIntentRule 处理"游泳/游艇/海鲜"等更细的强匹配规则。
 */
public enum LocalPlaceIntentCatalog {
    FOOD(
            List.of("美食", "餐饮", "吃饭", "餐厅", "饭店", "小吃", "海鲜", "水产", "咖啡", "甜品", "烧烤"),
            List.of("餐饮", "餐厅", "饭店", "酒楼", "小吃", "快餐", "咖啡", "甜品", "海鲜", "水产"),
            List.of("05"),
            List.of("美食", "餐厅", "本地特色餐饮")),
    SCENIC(
            List.of("景点", "景区", "游览", "观光", "公园", "海滨", "风景"),
            List.of("景点", "景区", "风景名胜", "公园", "博物馆", "纪念馆", "海滨浴场", "旅游"),
            List.of("11"),
            List.of("景点", "景区", "风景名胜")),
    HOTEL(
            List.of("住宿", "酒店", "宾馆", "民宿", "旅馆", "住店"),
            List.of("住宿", "酒店", "宾馆", "旅馆", "民宿", "度假村"),
            List.of("10"),
            List.of("酒店", "民宿", "住宿")),
    SHOPPING(
            List.of("购物", "逛街", "商场", "商城", "买东西", "商业街"),
            List.of("购物", "商场", "商城", "商业街", "专卖店", "购物中心"),
            List.of("06"),
            List.of("购物中心", "商场", "商业街")),
    ENTERTAINMENT(
            List.of("娱乐", "电影", "影院", "KTV", "唱歌", "桌游", "密室", "游乐园", "游艇", "帆船"),
            List.of("娱乐", "电影院", "影剧院", "KTV", "桌游", "密室", "游乐园", "游艇", "帆船", "游船", "码头"),
            List.of("08", "11"),
            List.of("休闲娱乐", "电影院", "娱乐场所")),
    GAMING(
            List.of("游戏", "电竞", "网吧", "网咖", "穿越火线", "三角洲", "开黑", "打游戏", "联机", "电竞馆"),
            List.of("网吧", "网咖", "电竞", "游戏", "网络", "娱乐"),
            List.of("08"),
            List.of("网吧", "网咖", "电竞馆")),
    SPORTS(
            List.of("运动", "体育", "健身", "游泳", "泳池", "球馆", "骑行", "滑雪"),
            List.of("体育", "运动", "健身", "游泳", "泳池", "球馆", "滑雪"),
            List.of("07"),
            List.of("体育场馆", "运动场馆", "健身")),
    CULTURE(
            List.of("文化", "博物馆", "美术馆", "展览", "看展", "图书馆", "剧院", "历史"),
            List.of("文化", "博物馆", "美术馆", "展览馆", "图书馆", "剧院", "文化馆"),
            List.of("14", "11"),
            List.of("博物馆", "美术馆", "文化场馆")),
    NIGHTLIFE(
            List.of("夜生活", "酒吧", "夜店", "清吧", "夜市", "夜游"),
            List.of("酒吧", "夜店", "清吧", "夜市", "夜游", "娱乐场所"),
            List.of("08", "05"),
            List.of("酒吧", "夜市", "夜生活")),
    TRANSPORT(
            List.of("交通", "车站", "火车站", "地铁", "机场", "停车", "停车场", "码头", "公交"),
            List.of("交通", "车站", "火车站", "地铁站", "机场", "停车场", "码头", "公交车站"),
            List.of("15"),
            List.of("交通设施", "车站", "停车场"));

    /** 用户口语别名，用于 classify 归类 */
    private final List<String> userAliases;
    /** 高德 POI 名称/类型文本中应出现的关键词 */
    private final List<String> amapTypeKeywords;
    /** 高德 POI 类型码前缀，拼上 "0000" 即为请求 types */
    private final List<String> amapTypeCodePrefixes;
    /** 网页搜索补充关键词 */
    private final List<String> searchKeywords;

    LocalPlaceIntentCatalog(
            List<String> userAliases,
            List<String> amapTypeKeywords,
            List<String> amapTypeCodePrefixes,
            List<String> searchKeywords) {
        this.userAliases = userAliases;
        this.amapTypeKeywords = amapTypeKeywords;
        this.amapTypeCodePrefixes = amapTypeCodePrefixes;
        this.searchKeywords = searchKeywords;
    }

    public List<String> userAliases() {
        return userAliases;
    }

    public List<String> amapTypeKeywords() {
        return amapTypeKeywords;
    }

    public List<String> amapTypeCodePrefixes() {
        return amapTypeCodePrefixes;
    }

    public List<String> amapTypesForRequest() {
        return amapTypeCodePrefixes.stream().map(prefix -> prefix + "0000").toList();
    }

    public List<String> searchKeywords() {
        return searchKeywords;
    }

    /**
     * 把用户意图归类到某个 POI 类别。
     * 按枚举声明顺序，第一个 userAliases 命中意图文本的类别胜出；都不命中返回空。
     *
     * 游泳/游艇/海鲜等细分词（SpecificIntentRule 覆盖）不归入粗类别：
     * 它们保持 CUSTOM 动态词语义，用户原词直接作为高德检索词，
     * 匹配阶段再由 SpecificIntentRule 做精度过滤，避免"游艇"被替换成
     * 娱乐类的预置检索词（如"休闲娱乐"）而丢失用户真实意图。
     */
    public static Optional<LocalPlaceIntentCatalog> classify(String userIntent) {
        if (userIntent == null || userIntent.isBlank()) return Optional.empty();
        if (SpecificIntentRule.forIntent(userIntent).isPresent()) return Optional.empty();
        return Arrays.stream(values())
                .filter(rule -> rule.userAliases.stream().anyMatch(userIntent::contains))
                .findFirst();
    }

    /**
     * 查询细分词强规则（游泳/游艇/海鲜）。
     * 供同包的 PlaceSearchServiceImpl 在 CUSTOM 分支调用：
     * 命中细分词时按强规则过滤，避免无关 POI（健身中心、照相馆等）混入。
     */
    static Optional<SpecificIntentRule> specificFor(String userIntent) {
        if (userIntent == null) return Optional.empty();
        return Arrays.stream(SpecificIntentRule.values())
                .filter(rule -> rule.userAliases.stream().anyMatch(userIntent::contains))
                .findFirst();
    }

    /**
     * 判断某个高德 POI 是否属于本类别。
     * 优先走 SpecificIntentRule 强规则（如"游泳"只匹配游泳馆，避免泛化到所有体育场所）；
     * 否则退化为"名称/类型文本包含关键词"或"类型码以前缀开头"，两者命中其一即可。
     */
    public boolean matches(String userIntent, String name, String type, String typeCode) {
        String combined = safe(name) + " " + safe(type);
        // 命中细分类规则时，只按细分规则判定，忽略宽泛类别
        Optional<SpecificIntentRule> specificRule = SpecificIntentRule.forIntent(userIntent);
        if (specificRule.isPresent()) return specificRule.get().matches(combined);
        boolean keywordMatch = amapTypeKeywords.stream().anyMatch(combined::contains);
        boolean typeCodeMatch =
                typeCode != null
                        && amapTypeCodePrefixes.stream().anyMatch(typeCode.trim()::startsWith);
        return keywordMatch || typeCodeMatch;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    /**
     * 细分词强规则：游泳/游艇/海鲜等口语词若归入粗类别（SPORTS/ENTERTAINMENT/FOOD），
     * 会导致"游艇"被替换成"休闲娱乐"这类预置检索词、且匹配阶段过度泛化
     * （例如"游泳"会匹配到所有体育场所）。因此这些词不参与粗类别归类，
     * 检索用用户原词，匹配阶段只按下面的 resultKeywords 做精度过滤。
     * 包内可见：PlaceSearchServiceImpl 的 CUSTOM 分支需要调用 matches 做过滤。
     */
    enum SpecificIntentRule {
        SWIMMING(List.of("游泳", "泳池"), List.of("游泳", "泳池", "游泳馆", "游泳场", "水上运动")),
        SAILING(List.of("游艇", "帆船"), List.of("游艇", "帆船", "游船", "码头", "船艇", "航海")),
        SEAFOOD(List.of("海鲜", "水产"), List.of("海鲜", "水产", "渔港", "鱼港", "海产"));

        private final List<String> userAliases;
        private final List<String> resultKeywords;

        SpecificIntentRule(List<String> userAliases, List<String> resultKeywords) {
            this.userAliases = userAliases;
            this.resultKeywords = resultKeywords;
        }

        static Optional<SpecificIntentRule> forIntent(String userIntent) {
            if (userIntent == null) return Optional.empty();
            return Arrays.stream(values())
                    .filter(rule -> rule.userAliases.stream().anyMatch(userIntent::contains))
                    .findFirst();
        }

        boolean matches(String combined) {
            return resultKeywords.stream().anyMatch(combined::contains);
        }
    }
}
