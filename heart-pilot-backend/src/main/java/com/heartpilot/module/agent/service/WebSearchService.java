package com.heartpilot.module.agent.service;

import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.common.util.concurrent.RateLimiter;
import com.heartpilot.infrastructure.ai.tool.CapabilityStatusRecorder;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;

/**
 * 送礼链路网页搜索服务（礼物搜索增强）。
 *
 * <p>搜索源与降级链：DuckDuckGo 免费 HTML 搜索（主）→ searchapi.io 付费搜索（兜底，保留原有付费额度逻辑） → 空结果（纯 AI
 * 推荐）。任何一层失败都不阻断送礼规划主流程，用户体验不断层。
 *
 * <p>可靠性设计：
 *
 * <ul>
 *   <li>Caffeine 本地缓存：同一关键词在 TTL（默认 10 分钟）内直接命中，不重复请求外部服务
 *   <li>Guava RateLimiter 全局限流：默认 40 次/分钟，超限排队 400ms，仍失败则放弃 DDG 直接降级
 *   <li>Spring Retry 注解：网络异常 / 429 / 反爬校验页按指数退避重试（1s → 3s，最多 3 次尝试）
 *   <li>熔断：连续 2 次失败后 24 小时内不再请求 DuckDuckGo，静默降级付费搜索或纯 AI
 * </ul>
 *
 * <p>注意：本类启用 @EnableRetry 全局代理；搜索入口 search() 通过 ObjectProvider 获取自身代理 再调用带 @Retryable 的
 * doDuckDuckGoSearch()，避免同类自调用绕过代理导致重试失效。
 */
@Service
@EnableRetry
public class WebSearchService {
    private static final Logger log = LoggerFactory.getLogger(WebSearchService.class);

    /** DuckDuckGo 免费 HTML 搜索端点（无需 API Key） */
    private static final String DDG_ENDPOINT = "https://html.duckduckgo.com/html/";

    /** searchapi.io 付费搜索端点（与现有 WebSearchTool 同一家，复用同一把 key） */
    private static final String SERP_ENDPOINT = "https://www.searchapi.io/api/v1/search";

    /** 伪装浏览器 UA，降低被反爬识别的概率 */
    private static final String BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                    + " (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36";

    /** 反爬校验页特征词：DuckDuckGo 异常页通常包含该词且没有搜索结果容器 */
    private static final String ANOMALY_MARK = "anomaly";

    /** 搜索结果容器选择器：DuckDuckGo HTML 版每条结果的结构是 #links 下 .result */
    private static final String RESULT_SELECTOR = "#links .result";

    /** DuckDuckGo 连续失败达到该次数即熔断（方案：连续 2 次异常页） */
    private static final int CIRCUIT_FAILURE_THRESHOLD = 2;

    /** 熔断持续时间：24 小时（“当天不再请求”） */
    private static final long CIRCUIT_OPEN_MILLIS = 24L * 60 * 60 * 1000;

    /** DDG 可重试异常：429 / 403 / 202 / 反爬校验页等临时失败，Spring Retry 会按退避策略重试 */
    public static final class RetryableSearchException extends RuntimeException {
        public RetryableSearchException(String message) {
            super(message);
        }
    }

    /** 单条搜索结果：标题 + 摘要 + 真实网页链接 */
    public record SearchResult(String title, String snippet, String url) {}

    private final HttpClient httpClient;
    private final Duration requestTimeout;
    private final Cache<String, List<SearchResult>> cache;
    private final RateLimiter rateLimiter;
    private final ObjectProvider<WebSearchService> selfProvider;
    private final CapabilityStatusRecorder statusRecorder;
    private final boolean ddgEnabled;
    private final int maxResultsPerKeyword;
    private final String searchApiKey;

    /** DDG 连续失败次数（熔断判据） */
    private final AtomicInteger consecutiveFailures = new AtomicInteger();

    /** DDG 熔断开启时间戳，0 表示未熔断 */
    private final AtomicReference<Long> circuitOpenedAt = new AtomicReference<>(0L);

    /** 最近一次实际命中的搜索源：duckduckgo / serpapi / 空（未搜到，纯 AI） */
    private final AtomicReference<String> lastSource = new AtomicReference<>("");

    /** SerpAPI 付费额度是否已耗尽（额度耗尽后向前端输出简单提示） */
    private final AtomicBoolean serpQuotaExhausted = new AtomicBoolean(false);

    public WebSearchService(
            ObjectProvider<WebSearchService> selfProvider,
            CapabilityStatusRecorder statusRecorder,
            @Value("${gift-search.duckduckgo.enabled:true}") boolean ddgEnabled,
            @Value("${gift-search.duckduckgo.max-results-per-keyword:5}") int maxResultsPerKeyword,
            @Value("${gift-search.duckduckgo.rate-per-minute:40}") double ratePerMinute,
            @Value("${gift-search.duckduckgo.cache-ttl-minutes:10}") int cacheTtlMinutes,
            @Value("${gift-search.duckduckgo.request-timeout-millis:6000}")
                    int requestTimeoutMillis,
            @Value("${search-api.api-key:}") String searchApiKey) {
        this.selfProvider = selfProvider;
        this.statusRecorder = statusRecorder;
        this.ddgEnabled = ddgEnabled;
        // 每组关键词最多取 5 条（方案建议放宽到 5 条让 LLM 自己筛），下限 1
        this.maxResultsPerKeyword = Math.max(1, maxResultsPerKeyword);
        this.searchApiKey = searchApiKey;
        this.cache =
                Caffeine.newBuilder()
                        .expireAfterWrite(Duration.ofMinutes(Math.max(1, cacheTtlMinutes)))
                        .maximumSize(2000)
                        .build();
        // 全局速率：每分钟次数换算为每秒许可数；RateLimiter 自带少量突发缓冲
        this.rateLimiter = RateLimiter.create(Math.max(1.0, ratePerMinute) / 60.0);
        this.httpClient =
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(5))
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .build();
        this.requestTimeout = Duration.ofMillis(Math.max(1000, requestTimeoutMillis));

        // 初始能力状态：DDG 免费搜索不需要 key，只要开着就按"正常"上报，
        // 避免前端启动后一直显示"未配置 key"；实际调用成功后会再刷新一次。
        // 若 DDG 关闭且没配付费 key，才报 NO_KEY。
        if (ddgEnabled) {
            statusRecorder.recordWebSearch(
                    CapabilityStatusRecorder.Status.OK, "DuckDuckGo 免费搜索（待首次调用验证）");
        } else if (searchApiKey == null || searchApiKey.isBlank()) {
            statusRecorder.recordWebSearch(
                    CapabilityStatusRecorder.Status.NO_KEY,
                    "未配置搜索服务（DuckDuckGo 已关闭且未配置 SerpAPI key）");
        } else {
            statusRecorder.recordWebSearch(
                    CapabilityStatusRecorder.Status.OK, "SerpAPI 付费搜索（待首次调用验证）");
        }
    }

    /**
     * 对外搜索入口（供 GiftRitualActionEnricher 调用）。 流程：缓存 → 限流 → 熔断判断 → DuckDuckGo（带重试）→ SerpAPI 兜底 →
     * 空结果（纯 AI）。
     */
    public List<SearchResult> search(String query) {
        if (query == null || query.isBlank()) return List.of();
        List<SearchResult> cached = cache.getIfPresent(query);
        if (cached != null) return cached;

        // 限流失败或 DDG 被熔断/关闭时，不排队硬等，直接走付费搜索兜底
        if (!acquirePermit() || !ddgEnabled || circuitOpen()) {
            return serpFallback(query);
        }
        try {
            // 经 self 代理调用，@Retryable 切面才生效（同类自调用会绕过代理）
            List<SearchResult> results = selfProvider.getObject().doDuckDuckGoSearch(query);
            if (results.isEmpty()) {
                // DDG 返回 200 但没搜到：不是异常页，不熔断，但仍尝试付费搜索兜底
                log.info("DuckDuckGo 未搜到结果 query={}，尝试 SerpAPI 兜底", query);
                return serpFallback(query);
            }
            consecutiveFailures.set(0);
            lastSource.set("duckduckgo");
            cache.put(query, results);
            // DDG 免费搜索可用：更新能力状态，覆盖"未配置 key"的初始误报
            statusRecorder.recordWebSearch(CapabilityStatusRecorder.Status.OK, "DuckDuckGo 免费搜索正常");
            return results;
        } catch (Exception e) {
            int failures = consecutiveFailures.incrementAndGet();
            log.warn("DuckDuckGo 搜索失败 query={} 连续失败={} 原因={}", query, failures, e.toString());
            if (failures >= CIRCUIT_FAILURE_THRESHOLD) {
                circuitOpenedAt.set(System.currentTimeMillis());
                log.warn("DuckDuckGo 连续失败 {} 次，熔断 24 小时，期间降级付费搜索/纯 AI", failures);
            }
            return serpFallback(query);
        }
    }

    /** 限流获取许可：先尝试立即获取，超限排队 400ms（短等待而不是直接拒绝），仍失败返回 false */
    private boolean acquirePermit() {
        if (rateLimiter.tryAcquire()) return true;
        try {
            return rateLimiter.tryAcquire(400, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            return false;
        }
    }

    /** 熔断是否开启：达到阈值后 24 小时内不再请求 DDG */
    private boolean circuitOpen() {
        long openedAt = circuitOpenedAt.get();
        return openedAt != 0L && System.currentTimeMillis() - openedAt < CIRCUIT_OPEN_MILLIS;
    }

    /**
     * DuckDuckGo HTML 搜索（可重试）。 退避策略：第一次失败等 1 秒，第二次等 3 秒（multiplier=3），加上初次尝试共 3 次。 429 / 403 / 202
     * / 反爬校验页视为可重试的临时失败；其余非 200 状态不重试、返回空。
     */
    @Retryable(
            retryFor = {IOException.class, RetryableSearchException.class},
            maxAttempts = 3,
            backoff = @Backoff(delay = 1000, multiplier = 3))
    public List<SearchResult> doDuckDuckGoSearch(String query)
            throws IOException, InterruptedException {
        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(URI.create(DDG_ENDPOINT + "?q=" + encode(query) + "&kl=cn-zh"))
                        .timeout(requestTimeout)
                        .header("User-Agent", BROWSER_UA)
                        .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                        .header(
                                "Accept",
                                "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                        .header("Referer", "https://duckduckgo.com/")
                        .GET()
                        .build();
        HttpResponse<String> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        int status = response.statusCode();
        if (status == 429 || status == 403 || status == 202) {
            // 429 限流、403/202 反爬校验：临时失败，交给 Spring Retry 指数退避重试
            throw new RetryableSearchException("duckduckgo 返回状态码 " + status);
        }
        if (status != 200) {
            log.warn("DuckDuckGo 返回非预期状态码 {}", status);
            return List.of();
        }
        String body = response.body();
        // 反爬校验页特征：出现 anomaly 且没有搜索结果容器（正常结果页始终含 #links）
        if (body != null && body.contains(ANOMALY_MARK) && !body.contains("#links")) {
            throw new RetryableSearchException("duckduckgo 反爬校验页（anomaly）");
        }
        return parseResults(body);
    }

    /** Jsoup 防御性解析：找不到结果容器或字段缺失时跳过该条 / 静默返回空列表， 绝不抛异常拖垮送礼规划请求；DDG 改版只影响单条结果，不影响整体流程。 */
    private List<SearchResult> parseResults(String html) {
        if (html == null || html.isBlank()) return List.of();
        try {
            Document doc = Jsoup.parse(html);
            Elements results = doc.select(RESULT_SELECTOR);
            List<SearchResult> parsed = new ArrayList<>();
            for (Element element : results) {
                if (parsed.size() >= maxResultsPerKeyword) break;
                Element link = element.selectFirst(".result__a");
                if (link == null) continue; // 结构变化时跳过该条
                Element snippetEl = element.selectFirst(".result__snippet");
                String url = resolveDuckDuckGoUrl(link.attr("href"));
                if (url.isBlank()) continue;
                parsed.add(
                        new SearchResult(
                                cleanText(link.text()),
                                snippetEl == null ? "" : cleanText(snippetEl.text()),
                                url));
            }
            return parsed;
        } catch (Exception e) {
            log.warn("DuckDuckGo 结果解析失败，按降级处理：{}", e.toString());
            return List.of();
        }
    }

    /**
     * DuckDuckGo HTML 版的结果链接是重定向地址（/l/?uddg=真实地址&rut=...）， 这里还原出真实目标 URL；还原失败时保留原地址，用户仍可点击经 DDG 跳转。
     */
    private String resolveDuckDuckGoUrl(String href) {
        if (href == null || href.isBlank()) return "";
        if (href.contains("uddg=")) {
            try {
                URI uri = URI.create(href);
                String query = uri.getRawQuery();
                if (query != null) {
                    for (String pair : query.split("&")) {
                        if (pair.startsWith("uddg=")) {
                            return URLDecoder.decode(
                                    pair.substring("uddg=".length()), StandardCharsets.UTF_8);
                        }
                    }
                }
            } catch (Exception ignored) {
                // 还原失败时保留原始地址
            }
        }
        return href;
    }

    /**
     * searchapi.io 付费搜索兜底（保留原有付费额度逻辑）。 额度耗尽 / 鉴权失败 / 其他错误都写入能力状态记录器（前端可读）， 额度耗尽时置
     * serpQuotaExhausted 标记，用于“额度简单提示”。
     */
    private List<SearchResult> serpFallback(String query) {
        if (searchApiKey == null || searchApiKey.isBlank()) {
            return List.of(); // 未配置付费 key：直接纯 AI 降级
        }
        try {
            String response =
                    HttpUtil.get(
                            SERP_ENDPOINT,
                            Map.of("q", query, "api_key", searchApiKey, "engine", "baidu"));
            JSONObject root = JSONUtil.parseObj(response);
            if (root.containsKey("error")) {
                String error = root.getStr("error", "");
                log.warn("SerpAPI 返回错误：{}", error);
                if (error.toLowerCase().contains("all of the searches") || error.contains("429")) {
                    serpQuotaExhausted.set(true);
                    statusRecorder.recordWebSearch(
                            CapabilityStatusRecorder.Status.QUOTA_EXHAUSTED,
                            "搜索 key 当月免费额度已用完，已自动降级为 DuckDuckGo 免费搜索");
                } else if (error.contains("401") || error.toLowerCase().contains("api key")) {
                    statusRecorder.recordWebSearch(
                            CapabilityStatusRecorder.Status.AUTH_FAILED, "搜索 key 无效或已过期");
                } else {
                    statusRecorder.recordWebSearch(
                            CapabilityStatusRecorder.Status.ERROR,
                            error.length() > 120 ? error.substring(0, 120) : error);
                }
                return List.of();
            }
            JSONArray items = root.getJSONArray("organic_results");
            if (items == null || items.isEmpty()) return List.of();
            statusRecorder.recordWebSearch(CapabilityStatusRecorder.Status.OK, "搜索服务正常");
            List<SearchResult> results = new ArrayList<>();
            for (int i = 0; i < items.size() && results.size() < maxResultsPerKeyword; i++) {
                JSONObject item = items.getJSONObject(i);
                String title = item.getStr("title", "");
                String snippet = item.getStr("snippet", "");
                String link = item.getStr("link", "");
                if (link.isBlank()) continue;
                results.add(new SearchResult(title, snippet, link));
            }
            if (!results.isEmpty()) {
                lastSource.set("serpapi");
                cache.put(query, results);
            }
            return results;
        } catch (Exception e) {
            log.warn("SerpAPI 兜底搜索失败 query={} 原因={}", query, e.toString());
            return List.of();
        }
    }

    /** 最近一次实际命中的搜索源：duckduckgo / serpapi / 空（未搜到，纯 AI） */
    public String lastSource() {
        return lastSource.get();
    }

    /** 付费搜索额度提示文案（解决“额度没有简单提示”：额度耗尽时前端可见提示，未配置 key 不提示） */
    public String quotaTip() {
        if (searchApiKey == null || searchApiKey.isBlank()) return "";
        return serpQuotaExhausted.get() ? "付费搜索额度已用完，已切换 DuckDuckGo 免费搜索" : "";
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String cleanText(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").trim();
    }
}
