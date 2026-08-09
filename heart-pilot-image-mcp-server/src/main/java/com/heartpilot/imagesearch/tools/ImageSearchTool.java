package com.heartpilot.imagesearch.tools;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import java.time.Instant;
import java.util.ArrayList;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class ImageSearchTool {

    private static final String API_URL = "https://api.pexels.com/v1/search";
    private static final String LICENSE_URL = "https://www.pexels.com/license/";
    private final String apiKey;

    public ImageSearchTool(@Value("${PEXELS_API_KEY:}") String apiKey) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
    }

    @Tool(description = "Search Pexels mood image URLs")
    public String searchImage(@ToolParam(description = "Plan text, classified locally before search") String query) {
        return String.join(",", searchMediumImages(query));
    }

    @Tool(description = "Search Pexels mood images with creator, source, and license metadata")
    public String searchImageAssets(
            @ToolParam(description = "Plan text, classified locally before search") String query) {
        return JSONUtil.toJsonStr(searchAssets(safeAtmosphereQuery(query), 6));
    }

    /**
     * 搜索中等尺寸的图片列表
     *
     * @param query
     * @return
     */
    public List<String> searchMediumImages(String query) {
        return searchAssets(safeAtmosphereQuery(query), 20).images().stream()
                .map(ImageAsset::thumbnailUrl)
                .toList();
    }

    private SearchResult searchAssets(String safeQuery, int limit) {
        if (apiKey.isBlank()) {
            return new SearchResult("Pexels", safeQuery, "DISABLED", List.of(), "未配置 PEXELS_API_KEY");
        }
        // 设置请求头（包含API密钥）
        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", apiKey);

        // 设置请求参数（仅包含query，可根据文档补充page、per_page等参数）
        Map<String, Object> params = new HashMap<>();
        params.put("query", safeQuery);
        params.put("orientation", "landscape");
        params.put("per_page", Math.max(1, Math.min(limit, 20)));

        // 发送 GET 请求
        String response = HttpUtil.createGet(API_URL)
                .addHeaders(headers)
                .form(params)
                .execute()
                .body();

        List<ImageAsset> assets = new ArrayList<>();
        JSONUtil.parseObj(response)
                .getJSONArray("photos")
                .stream()
                .limit(limit)
                .map(photoObj -> (JSONObject) photoObj)
                .forEach(
                        photo -> {
                            JSONObject src = photo.getJSONObject("src");
                            if (src == null || StrUtil.isBlank(src.getStr("medium"))) return;
                            assets.add(
                                    new ImageAsset(
                                            photo.getLong("id", 0L),
                                            src.getStr("large", src.getStr("medium")),
                                            src.getStr("medium"),
                                            photo.getStr("url", ""),
                                            photo.getStr("photographer", ""),
                                            photo.getStr("photographer_url", ""),
                                            photo.getStr("alt", "Mood image"),
                                            "Pexels License",
                                            LICENSE_URL,
                                            false,
                                            Instant.now()));
                        });
        return new SearchResult(
                "Pexels", safeQuery, assets.isEmpty() ? "EMPTY" : "LIVE", assets, "Photos provided by Pexels");
    }

    private String safeAtmosphereQuery(String value) {
        String text = value == null ? "" : value.toLowerCase();
        if (text.matches(".*(餐|food|dinner|cafe).*")) return "romantic dinner ambience";
        if (text.matches(".*(公园|散步|nature|walk).*")) return "couple nature walk ambience";
        if (text.matches(".*(展|艺术|gallery|museum).*")) return "art gallery date ambience";
        if (text.matches(".*(旅行|酒店|travel|hotel).*")) return "romantic travel ambience";
        return "warm couple date ambience";
    }

    public record SearchResult(
            String provider, String query, String sourceStatus, List<ImageAsset> images, String notice) {}

    public record ImageAsset(
            long id,
            String imageUrl,
            String thumbnailUrl,
            String pageUrl,
            String photographer,
            String photographerUrl,
            String alt,
            String licenseName,
            String licenseUrl,
            boolean attributionRequired,
            Instant licenseCheckedAt) {}
}
