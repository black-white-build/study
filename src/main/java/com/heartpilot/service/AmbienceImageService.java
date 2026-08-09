package com.heartpilot.service;

import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Retrieves mood images without sending the user's original plan or location to Pexels. */
@Service
public class AmbienceImageService {
    private static final String API_URL = "https://api.pexels.com/v1/search";
    private static final String LICENSE_URL = "https://www.pexels.com/license/";

    private final String apiKey;

    public AmbienceImageService(@Value("${PEXELS_API_KEY:}") String apiKey) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
    }

    public SearchResult search(String objective, int limit) {
        String query = safeAtmosphereQuery(objective);
        if (apiKey.isBlank()) {
            return new SearchResult(
                    "Pexels", query, List.of(), "DISABLED", "未配置 PEXELS_API_KEY", Instant.now());
        }
        try {
            String body =
                    HttpUtil.createGet(API_URL)
                            .header("Authorization", apiKey)
                            .form(
                                    Map.of(
                                            "query",
                                            query,
                                            "orientation",
                                            "landscape",
                                            "locale",
                                            "en-US",
                                            "per_page",
                                            Math.max(1, Math.min(limit, 12))))
                            .execute()
                            .body();
            JSONObject root = JSONUtil.parseObj(body);
            JSONArray photos = root.getJSONArray("photos");
            List<AmbienceImage> images = new ArrayList<>();
            if (photos != null) {
                for (int index = 0; index < Math.min(limit, photos.size()); index++) {
                    JSONObject photo = photos.getJSONObject(index);
                    JSONObject src = photo.getJSONObject("src");
                    if (src == null) continue;
                    images.add(
                            new AmbienceImage(
                                    photo.getLong("id", 0L),
                                    src.getStr("large", src.getStr("landscape", "")),
                                    src.getStr("medium", ""),
                                    photo.getStr("url", ""),
                                    photo.getStr("photographer", ""),
                                    photo.getStr("photographer_url", ""),
                                    photo.getStr("alt", "方案氛围图"),
                                    photo.getStr("avg_color", "#E9E4DA"),
                                    photo.getInt("width", 0),
                                    photo.getInt("height", 0),
                                    "Pexels License",
                                    LICENSE_URL,
                                    false,
                                    "建议展示摄影师与 Pexels 链接；实际使用仍须遵守许可页中的禁止用途。",
                                    Instant.now()));
                }
            }
            return new SearchResult(
                    "Pexels",
                    query,
                    images,
                    images.isEmpty() ? "EMPTY" : "LIVE",
                    images.isEmpty() ? "Pexels 未返回匹配图片" : "Photos provided by Pexels",
                    Instant.now());
        } catch (Exception exception) {
            return new SearchResult(
                    "Pexels", query, List.of(), "DEGRADED", "图片检索暂不可用", Instant.now());
        }
    }

    String safeAtmosphereQuery(String objective) {
        String text = objective == null ? "" : objective.toLowerCase();
        if (containsAny(text, "餐", "美食", "晚饭", "午饭", "咖啡")) return "romantic dinner ambience";
        if (containsAny(text, "公园", "散步", "户外", "自然")) return "couple nature walk ambience";
        if (containsAny(text, "展", "博物馆", "艺术")) return "art gallery date ambience";
        if (containsAny(text, "电影", "影院")) return "cinema date ambience";
        if (containsAny(text, "旅行", "酒店", "度假")) return "romantic travel ambience";
        return "warm couple date ambience";
    }

    private boolean containsAny(String text, String... values) {
        for (String value : values) if (text.contains(value)) return true;
        return false;
    }

    public record SearchResult(
            String provider,
            String query,
            List<AmbienceImage> images,
            String sourceStatus,
            String notice,
            Instant searchedAt) {
        public SearchResult {
            images = images == null ? List.of() : images;
        }
    }

    public record AmbienceImage(
            long id,
            String imageUrl,
            String thumbnailUrl,
            String pageUrl,
            String photographer,
            String photographerUrl,
            String alt,
            String averageColor,
            int width,
            int height,
            String licenseName,
            String licenseUrl,
            boolean attributionRequired,
            String usageNotice,
            Instant licenseCheckedAt) {}
}
