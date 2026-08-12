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

@Component
public class RateLimitFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private record Window(long minute, AtomicInteger count) {}

    private final Cache<String, Window> local;
    private final int defaultLimit;
    private final int authLimit;
    private final ObjectMapper mapper;
    private final StringRedisTemplate redis;
    private final boolean redisEnabled;
    private final Set<String> trustedProxies;
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
        this.redis = redis.getIfAvailable();
        this.trustedProxies =
                Arrays.stream(trustedProxies.split(","))
                        .map(String::trim)
                        .filter(value -> !value.isBlank())
                        .collect(java.util.stream.Collectors.toUnmodifiableSet());
        this.local =
                Caffeine.newBuilder()
                        .maximumSize(maximumSize)
                        .expireAfterAccess(Duration.ofMinutes(3))
                        .build();
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getRequestURI().endsWith("/health")) {
            chain.doFilter(request, response);
            return;
        }

        boolean authenticationEndpoint = request.getRequestURI().contains("/auth/");
        int limit = authenticationEndpoint ? authLimit : defaultLimit;
        String identity = authenticationEndpoint ? "ip:" + clientIp(request) : identity(request);
        long minute = Instant.now().getEpochSecond() / 60;
        long count = increment((authenticationEndpoint ? "auth:" : "api:") + identity, minute);
        response.setHeader("X-RateLimit-Limit", String.valueOf(limit));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(Math.max(0, limit - count)));
        if (count > limit) {
            response.setStatus(429);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            mapper.writeValue(
                    response.getWriter(),
                    Map.of("code", "RATE_LIMITED", "message", "请求过于频繁，请稍后再试"));
            return;
        }
        chain.doFilter(request, response);
    }

    private String identity(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null
                && authentication.isAuthenticated()
                && !"anonymousUser".equals(authentication.getName())) {
            return "user:" + authentication.getName();
        }
        return "ip:" + clientIp(request);
    }

    private String clientIp(HttpServletRequest request) {
        String remoteAddress = request.getRemoteAddr();
        if (!trustedProxies.contains(remoteAddress)) return remoteAddress;
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded == null || forwarded.isBlank()) return remoteAddress;
        return forwarded.split(",", 2)[0].trim();
    }

    private long increment(String identity, long minute) {
        if (redisEnabled && redis != null) {
            try {
                String redisKey = "rate:" + minute + ":" + identity;
                Long count = redis.opsForValue().increment(redisKey);
                if (count != null && count == 1) redis.expire(redisKey, Duration.ofMinutes(2));
                if (count != null) return count;
            } catch (RuntimeException exception) {
                metrics.counter("heartpilot.rate_limit.redis_fallbacks").increment();
                log.warn("Redis rate limiter unavailable; using single-instance local fallback");
            }
        }
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
