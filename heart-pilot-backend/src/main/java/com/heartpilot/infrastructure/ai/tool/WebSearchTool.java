package com.heartpilot.infrastructure.ai.tool;

import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 网页搜索工具，供 Agent 调用以获取公开网页资料。
 * 基于 searchapi.io（百度引擎）实现，结果会过滤掉与心理健康/医学论文无关的噪音来源，
 * 并按"目标城市"做地域相关性过滤，避免返回其他城市的地点信息。
 */
@Component
public class WebSearchTool {
    private static final Logger log = LoggerFactory.getLogger(WebSearchTool.class);
    /** searchapi.io 搜索接口地址 */
    private static final String URL = "https://www.searchapi.io/api/v1/search";
    /** 与本地生活场景无关的噪音关键词，命中则丢弃该条结果（医学/学术类网页对找地点无帮助） */
    private static final Set<String> IRRELEVANT = Set.of("世界卫生组织", "心理健康", "精神卫生", "医学论文", "学术文档");
    /** searchapi.io API Key，来自配置项 search-api.api-key；为空时搜索降级为返回空结果 */
    private final String apiKey;

    public WebSearchTool(@Value("${search-api.api-key:}") String apiKey) {
        this.apiKey = apiKey;
    }

    /**
     * Agent 可调用的通用网页搜索。
     *
     * @param query 搜索关键词
     * @return 格式化后的网页结果文本（标题+摘要+来源链接）
     */
    @Tool(description = "搜索公开网页信息，仅用于非敏感的公开资料")
    public String searchWeb(@ToolParam(description = "搜索关键词") String query) {
        return format(searchResults(query, null, 5), null);
    }

    /**
     * 搜索本地地点信息（默认返回 8 条）。
     *
     * @param city     目标城市
     * @param keywords 地点关键词（如"咖啡馆""书店"）
     * @return 格式化后的本地结果文本
     */
    public String searchLocalPlaces(String city, String keywords) {
        return searchLocalPlaces(city, keywords, 8);
    }

    /**
     * 搜索本地地点信息，可指定返回条数。
     * 在查询词中拼入当前年份与"营业状态/路线/高德地图/大众点评"等词，提升结果时效性与本地相关性。
     */
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

    /**
     * 搜索本地地点并要求结果同时命中指定关键词。
     *
     * @param city           目标城市
     * @param keywords       地点关键词
     * @param requiredKeyword 结果标题或摘要中必须包含的词（用于二次筛选）
     * @param limit          最大返回条数
     * @return 格式化结果；无命中时返回明确提示文案
     */
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

    /**
     * 供 Service 层直接获取结构化搜索结果（不做文本格式化）。
     */
    public List<WebResult> searchWebResults(String query, int limit) {
        return searchResults(query, null, limit);
    }

    /**
     * 调用 searchapi.io 并清洗结果。
     * 无 API Key 或请求异常时静默返回空列表（搜索是增强能力，不应阻断主流程）。
     *
     * @param query        搜索词
     * @param requiredCity 需要过滤的目标城市，null 表示不做地域过滤
     * @param limit        最多保留条数
     */
    private List<WebResult> searchResults(String query, String requiredCity, int limit) {
        if (apiKey == null || apiKey.isBlank()) {
            return List.of();
        }
        try {
            String response =
                    HttpUtil.get(URL, Map.of("q", query, "api_key", apiKey, "engine", "baidu"));
            JSONObject root = JSONUtil.parseObj(response);
            if (root.containsKey("error")) {
                log.warn("Web search API returned an error: {}", root.get("error"));
                return List.of();
            }
            JSONArray items = root.getJSONArray("organic_results");
            if (items == null || items.isEmpty()) return List.of();
            List<WebResult> results = new ArrayList<>();
            for (int i = 0; i < items.size() && results.size() < limit; i++) {
                JSONObject item = items.getJSONObject(i);
                String title = item.getStr("title", "未命名地点");
                String snippet = item.getStr("snippet", "");
                String link = item.getStr("link", "");
                String combined = title + " " + snippet;
                // 丢弃医学/学术类噪音来源
                if (IRRELEVANT.stream().anyMatch(combined::contains)) continue;
                // 地域过滤：指定城市时只保留正文确实提到该城市的结果，防止混入其他城市
                if (requiredCity != null
                        && !requiredCity.isBlank()
                        && !containsRequestedLocation(combined, requiredCity)) continue;
                if (!link.isBlank()) results.add(new WebResult(title, snippet, link));
            }
            return results;
        } catch (Exception e) {
            log.warn(
                    "Web search request failed: requiredCity={}, error={}",
                    requiredCity,
                    e.toString());
            return List.of();
        }
    }

    /**
     * 判断文本中是否确实提到了目标地点（归一化后做包含匹配）。
     */
    static boolean containsRequestedLocation(String text, String requestedLocation) {
        String keyword = mostSpecificLocationName(requestedLocation);
        return !keyword.isBlank() && normalizeLocationText(text).contains(keyword);
    }

    /**
     * 从"XX省XX市"这类全称中提取最具体的地名（去掉省/自治区等前缀）。
     */
    static String mostSpecificLocationName(String value) {
        if (value == null) return "";
        String location = value.replaceAll("[\\s,，、/\\-|]+", "").trim();
        location = location.replaceFirst("^.*?(?:特别行政区|壮族自治区|回族自治区|维吾尔自治区|自治区|省)", "");
        return normalizeLocationText(location);
    }

    /**
     * 归一化地名文本：去除空白、标点与"省/市/区/县"等行政后缀，便于做模糊包含判断。
     */
    private static String normalizeLocationText(String value) {
        if (value == null) return "";
        return value.replaceAll("[\\s,，、/\\-|]+", "")
                .replaceAll("(?:特别行政区|壮族自治区|回族自治区|维吾尔自治区|自治区|自治州|地区|盟|省|市|区|县)", "")
                .trim();
    }

    /**
     * 把搜索结果拼接为给 AI/用户阅读的 Markdown 列表文本；空结果返回友好提示。
     */
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

    /** 单条网页搜索结果 record */
    public record WebResult(String title, String snippet, String url) {}
}
