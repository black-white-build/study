package com.heartpilot.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class RateLimitFilter extends OncePerRequestFilter {
    private record Window(long minute, AtomicInteger count) {}

    private final Map<String, Window> local = new ConcurrentHashMap<>();
    private final int limit;
    private final ObjectMapper mapper;
    private final StringRedisTemplate redis;
    private final boolean redisEnabled;

    public RateLimitFilter(
            @Value("${app.rate-limit.requests-per-minute:120}") int limit,
            @Value("${app.rate-limit.redis-enabled:false}") boolean redisEnabled,
            ObjectMapper mapper,
            ObjectProvider<StringRedisTemplate> redis) {
        this.limit = limit;
        this.redisEnabled = redisEnabled;
        this.mapper = mapper;
        this.redis = redis.getIfAvailable();
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        if (req.getRequestURI().endsWith("/health")) {
            chain.doFilter(req, res);
            return;
        }
        long minute = Instant.now().getEpochSecond() / 60;
        String identity =
                req.getRemoteAddr()
                        + ":"
                        + Optional.ofNullable(req.getHeader("Authorization")).orElse("");
        String key = fingerprint(identity);
        long count = increment(key, minute);
        int remaining = (int) Math.max(0, limit - count);
        res.setHeader("X-RateLimit-Remaining", String.valueOf(remaining));
        if (count > limit) {
            res.setStatus(429);
            res.setContentType(MediaType.APPLICATION_JSON_VALUE);
            mapper.writeValue(
                    res.getWriter(), Map.of("code", "RATE_LIMITED", "message", "请求过于频繁，请稍后再试"));
            return;
        }
        chain.doFilter(req, res);
    }

    private long increment(String key, long minute) {
        if (redisEnabled && redis != null) {
            try {
                String redisKey = "rate:" + minute + ":" + key;
                Long n = redis.opsForValue().increment(redisKey);
                if (n != null && n == 1) redis.expire(redisKey, Duration.ofMinutes(2));
                if (n != null) return n;
            } catch (Exception ignored) {
            }
        }
        Window w =
                local.compute(
                        key,
                        (k, old) ->
                                old == null || old.minute() != minute
                                        ? new Window(minute, new AtomicInteger())
                                        : old);
        if (local.size() > 10000)
            local.entrySet().removeIf(e -> e.getValue().minute() < minute - 2);
        return w.count().incrementAndGet();
    }

    private String fingerprint(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)),
                            0,
                            12);
        } catch (Exception e) {
            return Integer.toHexString(value.hashCode());
        }
    }
}
