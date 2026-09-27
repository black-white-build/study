package com.heartpilot.module.agent.service.impl;

import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.heartpilot.module.agent.service.AmbienceImageService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 为方案检索氛围（mood）配图，但绝不把用户原始计划或地点传给 Pexels。
 * Retrieves mood images without sending the user's original plan or location to Pexels.
 *
 * 隐私与降级设计：
 * - 先把目标归一为一个通用英文氛围关键词（safeAtmosphereQuery），只用该关键词搜图，
 *   用户具体城市/店名不会外泄
 * - 未配置 Key → DISABLED；请求异常 → DEGRADED；无结果 → EMPTY；都返回空列表但不抛错，
 *   配图失败不影响主流程
 */
@Service
public class AmbienceImageServiceImpl implements AmbienceImageService {
    /** Pexels 图片搜索接口地址 */
    private static final String API_URL = "https://api.pexels.com/v1/search";
    /** Pexels 许可说明页地址，随图片结果返回供前端展示 */
    private static final String LICENSE_URL = "https://www.pexels.com/license/";

    /** Pexels API Key，来自配置 PEXELS_API_KEY，空串表示未启用 */
    private final String apiKey;

    /**
     * 构造器注入 Pexels Key，空值归一为空串。
     */
    public AmbienceImageServiceImpl(@Value("${PEXELS_API_KEY:}") String apiKey) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
    }

    /**
     * 按目标关键词检索氛围图。
     * 先把目标映射为通用英文氛围词再请求 Pexels（横构图、en-US），
     * 单页数量夹在 1~12 之间；异常统一降级为 DEGRADED，不向上抛。
     *
     * @param objective 用户目标
     * @param limit 期望图片数量
     * @return 检索结果（含状态 LIVE/EMPTY/DISABLED/DEGRADED）
     */
    @Override
    public SearchResult search(String objective, int limit) {
        // 归一为通用英文氛围词，避免把用户具体计划/地点发给第三方
        String query = safeAtmosphereQuery(objective);
        if (apiKey.isBlank()) {
            return new SearchResult(
                    "Pexels", query, List.of(), "DISABLED", "未配置 PEXELS_API_KEY", Instant.now());
        }
        try {
            // 横构图、英文环境，per_page 限制 1~12 张
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

    /**
     * 把中文目标关键词映射为通用英文氛围搜索词。
     * 按餐/户外/展/电影/旅行等粗类别匹配，都不命中时用默认"温馨约会"氛围；
     * 只输出泛化词，不包含任何具体地点信息。
     */
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
}
