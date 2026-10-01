package com.heartpilot.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 接口限流过滤器。
 * 过滤顺序：位于 JwtAuthenticationFilter 之后（见 SecurityConfig），此时 SecurityContext 已有用户信息。
 * 按"已登录用户 ID / 客户端 IP"为维度做每分钟滑动窗口计数：
 * 认证类接口（/auth/...）使用更严格的 authLimit，其余接口使用 defaultLimit。
 * 优先使用 Redis 计数以支持多实例部署；Redis 不可用时降级到本机 Caffeine 缓存（仅单实例有效），
 * 并通过 Micrometer 指标记录降级事件。超限返回 429，并在响应头暴露限流配额。
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    /** 单个时间窗口：minute 标识窗口所属分钟，count 为该窗口内已请求次数 */
    private record Window(long minute, AtomicInteger count) {}

    /** Redis 不可用时的本机兜底计数器（Caffeine 缓存） */
    private final Cache<String, Window> local;
    /** 普通接口每分钟限流阈值 */
    private final int defaultLimit;
    /** 认证接口（登录/注册等）每分钟限流阈值，防暴力破解 */
    private final int authLimit;
    /** 用于把 429 错误信息序列化为 JSON */
    private final ObjectMapper mapper;
    /** Redis 计数器客户端，可空（未启用或不可用时使用本机缓存） */
    private final StringRedisTemplate redis;
    /** 是否启用 Redis 分布式限流 */
    private final boolean redisEnabled;
    /** 可信代理 IP 列表，仅从这些代理转发时才采信 X-Forwarded-For，防止伪造真实 IP */
    private final Set<String> trustedProxies;
    /** 指标注册器，用于统计 Redis 降级次数 */
    private final MeterRegistry metrics;

    public RateLimitFilter(
            @Value("${app.rate-limit.requests-per-minute:120}") int defaultLimit,
            @Value("${app.rate-limit.auth-requests-per-minute:20}") int authLimit,
            @Value("${app.rate-limit.redis-enabled:false}") boolean redisEnabled,
            @Value("${app.rate-limit.trusted-proxies:127.0.0.1}") String trustedProxies,
            @Value("${app.rate-limit.local-cache-maximum-size:10000}") long maximumSize,
            ObjectMapper mapper,
            MeterRegistry metrics,
            ObjectProvider<StringRedisTemplate> redis) {
        this.defaultLimit = defaultLimit;
        this.authLimit = authLimit;
        this.redisEnabled = redisEnabled;
        this.mapper = mapper;
        this.metrics = metrics;
        // 用 ObjectProvider 延迟获取，避免未配置 Redis 时容器启动失败
        this.redis = redis.getIfAvailable();
        this.trustedProxies =
                Arrays.stream(trustedProxies.split(","))
                        .map(String::trim)
                        .filter(value -> !value.isBlank())
                        .collect(java.util.stream.Collectors.toUnmodifiableSet());
        // 本机窗口缓存：最多容纳 maximumSize 个键，3 分钟未访问自动过期（覆盖一个限流窗口即可）
        this.local =
                Caffeine.newBuilder()
                        .maximumSize(maximumSize)
                        .expireAfterAccess(Duration.ofMinutes(3))
                        .build();
    }

    /**
     * 单次请求限流执行逻辑。
     * 实现要点：跳过 /health 探活请求；认证接口按 IP 用 authLimit 严格限流，其余接口按登录用户 ID 用 defaultLimit；
     * 计数优先走 Redis 原子 incr（多实例共享），Redis 异常时降级到本机 Caffeine 窗口计数；
     * 无论是否超限都在响应头写入 X-RateLimit-Limit / X-RateLimit-Remaining；超限直接写 429 JSON 并中断过滤链。
     */
    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // 健康检查不限流，避免探活被误杀
        if (request.getRequestURI().endsWith("/health")) {
            chain.doFilter(request, response);
            return;
        }

        // 认证接口按 IP 限流（此时用户尚未登录），其余接口优先按用户 ID 限流
        boolean authenticationEndpoint = request.getRequestURI().contains("/auth/");
        int limit = authenticationEndpoint ? authLimit : defaultLimit;
        String identity = authenticationEndpoint ? "ip:" + clientIp(request) : identity(request);
        // 以分钟为窗口：当前 epoch 秒 / 60 即窗口编号，跨分钟自动开启新窗口
        long minute = Instant.now().getEpochSecond() / 60;
        long count = increment((authenticationEndpoint ? "auth:" : "api:") + identity, minute);
        // 暴露限流配额给前端展示
        response.setHeader("X-RateLimit-Limit", String.valueOf(limit));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(Math.max(0, limit - count)));
        if (count > limit) {
            // 超限直接返回 429，不再放行到后续过滤器与控制器
            response.setStatus(429);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            mapper.writeValue(
                    response.getWriter(),
                    Map.of("code", "RATE_LIMITED", "message", "请求过于频繁，请稍后再试"));
            return;
        }
        chain.doFilter(request, response);
    }

    /**
     * 解析限流身份：已登录用户按用户 ID，未登录按客户端 IP。
     */
    private String identity(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null
                && authentication.isAuthenticated()
                && !"anonymousUser".equals(authentication.getName())) {
            return "user:" + authentication.getName();
        }
        return "ip:" + clientIp(request);
    }

    /**
     * 获取真实客户端 IP。仅当请求来自可信代理时才解析 X-Forwarded-For 首段，
     * 否则直接取 remoteAddr，防止客户端伪造头绕过单 IP 限流。
     */
    private String clientIp(HttpServletRequest request) {
        String remoteAddress = request.getRemoteAddr();
        if (!trustedProxies.contains(remoteAddress)) return remoteAddress;
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded == null || forwarded.isBlank()) return remoteAddress;
        // X-Forwarded-For 形如 "client, proxy1, proxy2"，取第一个即真实客户端
        return forwarded.split(",", 2)[0].trim();
    }

    /**
     * 计数：优先 Redis 原子自增实现分布式限流；失败则降级本机 Caffeine 窗口计数。
     * @param identity 带前缀的限流身份（user:xxx / ip:xxx / auth:ip:xxx）
     * @param minute 当前分钟窗口编号
     * @return 该窗口内累计请求次数
     */
    private long increment(String identity, long minute) {
        if (redisEnabled && redis != null) {
            try {
                // key 含分钟窗口，天然按分钟分段；incr 是原子操作
                String redisKey = "rate:" + minute + ":" + identity;
                Long count = redis.opsForValue().increment(redisKey);
                // 首次计数时设置过期，2 分钟后自动清理（窗口过后不再需要）
                if (count != null && count == 1) redis.expire(redisKey, Duration.ofMinutes(2));
                if (count != null) return count;
            } catch (RuntimeException exception) {
                // Redis 故障不阻断业务，降级本机限流并上报指标
                metrics.counter("heartpilot.rate_limit.redis_fallbacks").increment();
                log.warn("Redis rate limiter unavailable; using single-instance local fallback");
            }
        }
        // 本机兜底：跨分钟时重置计数窗口，保证按分钟限流
        Window window =
                local.asMap()
                        .compute(
                                identity,
                                (key, old) ->
                                        old == null || old.minute() != minute
                                                ? new Window(minute, new AtomicInteger())
                                                : old);
        return window.count().incrementAndGet();
    }
}
