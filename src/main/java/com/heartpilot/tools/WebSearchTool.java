package com.heartpilot.tools;

import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class WebSearchTool {
    private static final String URL = "https://www.searchapi.io/api/v1/search";
    private static final Set<String> IRRELEVANT = Set.of("世界卫生组织", "心理健康", "精神卫生", "医学论文", "学术文档");
    private final String apiKey;

    public WebSearchTool(@Value("${search-api.api-key:}") String apiKey) {
        this.apiKey = apiKey;
    }

    @Tool(description = "搜索公开网页信息，仅用于非敏感的公开资料")
    public String searchWeb(@ToolParam(description = "搜索关键词") String query) {
        return format(searchResults(query, null, 5), null);
    }

    public String searchLocalPlaces(String city, String keywords) {
        return searchLocalPlaces(city, keywords, 8);
    }

    public String searchLocalPlaces(String city, String keywords, int limit) {
        String query =
                city
                        + " "
                        + keywords
                        + " "
                        + LocalDate.now().getYear()
                        + " 最新营业状态 实时路线 地址 本地推荐 高德地图 大众点评";
        return format(searchResults(query, city, limit), city);
    }

    public String searchLocalPlaces(
            String city, String keywords, String requiredKeyword, int limit) {
        String query =
                city
                        + " "
                        + keywords
                        + " "
                        + LocalDate.now().getYear()
                        + " 最新营业状态 地址 本地推荐 高德地图 大众点评";
        List<WebResult> results =
                searchResults(query, city, limit).stream()
                        .filter(
                                result ->
                                        (result.title() + " " + result.snippet())
                                                .contains(requiredKeyword))
                        .toList();
        if (results.isEmpty()) return "暂未检索到同时包含“" + city + "”和“" + requiredKeyword + "”的可靠公开来源。";
        return format(results, city);
    }

    public List<WebResult> searchWebResults(String query, int limit) {
        return searchResults(query, null, limit);
    }

    private List<WebResult> searchResults(String query, String requiredCity, int limit) {
        if (apiKey == null || apiKey.isBlank()) {
            return List.of();
        }
        try {
            String response =
                    HttpUtil.get(URL, Map.of("q", query, "api_key", apiKey, "engine", "baidu"));
            JSONObject root = JSONUtil.parseObj(response);
            if (root.containsKey("error")) return List.of();
            JSONArray items = root.getJSONArray("organic_results");
            if (items == null || items.isEmpty()) return List.of();
            List<WebResult> results = new ArrayList<>();
            for (int i = 0; i < items.size() && results.size() < limit; i++) {
                JSONObject item = items.getJSONObject(i);
                String title = item.getStr("title", "未命名地点");
                String snippet = item.getStr("snippet", "");
                String link = item.getStr("link", "");
                String combined = title + " " + snippet;
                if (IRRELEVANT.stream().anyMatch(combined::contains)) continue;
                if (requiredCity != null
                        && !requiredCity.isBlank()
                        && !combined.contains(requiredCity)) continue;
                if (!link.isBlank()) results.add(new WebResult(title, snippet, link));
            }
            return results;
        } catch (Exception e) {
            return List.of();
        }
    }

    private String format(List<WebResult> results, String requiredCity) {
        if (results.isEmpty()) {
            return requiredCity == null || requiredCity.isBlank()
                    ? "暂未检索到可靠的公开网页来源。"
                    : "暂未检索到明确包含“" + requiredCity + "”的公开来源，未混入其他城市结果。";
        }
        return results.stream()
                .map(
                        result ->
                                "- "
                                        + result.title()
                                        + "\n  "
                                        + result.snippet()
                                        + "\n  来源："
                                        + result.url())
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
    }

    public record WebResult(String title, String snippet, String url) {}
}
