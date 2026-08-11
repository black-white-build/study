package com.heartpilot.module.agent.service.impl;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/** Central mapping between user language and AMap POI categories. */
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

    private final List<String> userAliases;
    private final List<String> amapTypeKeywords;
    private final List<String> amapTypeCodePrefixes;
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

    public static Optional<LocalPlaceIntentCatalog> classify(String userIntent) {
        if (userIntent == null || userIntent.isBlank()) return Optional.empty();
        return Arrays.stream(values())
                .filter(rule -> rule.userAliases.stream().anyMatch(userIntent::contains))
                .findFirst();
    }

    public boolean matches(String userIntent, String name, String type, String typeCode) {
        String combined = safe(name) + " " + safe(type);
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

    private enum SpecificIntentRule {
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
