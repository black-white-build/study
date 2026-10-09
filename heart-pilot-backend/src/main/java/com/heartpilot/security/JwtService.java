package com.heartpilot.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * JWT 令牌服务。 负责访问令牌的签发与校验：使用 HMAC-SHA 对称密钥对令牌签名， 密钥与有效期均来自配置（app.jwt.secret /
 * app.jwt.access-token-minutes）。 令牌无状态，服务端不存储会话，校验仅靠签名与过期时间。
 */
@Service
public class JwtService {
    /** 签名密钥，由配置中的 secret 字节按 HMAC-SHA 要求派生 */
    private final SecretKey key;

    /** 访问令牌有效期 */
    private final Duration ttl;

    /**
     * 构造令牌服务，校验密钥长度并初始化密钥与 TTL。
     *
     * @param secret 配置文件中的 JWT 密钥，UTF-8 字节长度至少 32
     * @param minutes 访问令牌有效分钟数，默认 120 分钟
     * @throws IllegalStateException 密钥不足 32 字节时抛出（无法满足 HS256 强度要求）
     */
    public JwtService(
            @Value("${app.jwt.secret}") String secret,
            @Value("${app.jwt.access-token-minutes:120}") long minutes) {
        if (secret.getBytes(StandardCharsets.UTF_8).length < 32)
            throw new IllegalStateException("JWT_SECRET 至少需要 32 字节");
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.ttl = Duration.ofMinutes(minutes);
    }

    /**
     * 为指定用户签发访问令牌。
     *
     * @param userId 用户 ID，写入令牌 subject
     * @param role 用户角色（如 USER/ADMIN），写入 role 自定义声明
     * @param nickname 用户昵称，写入 nickname 自定义声明供前端展示
     * @return 签名后的紧凑 JWT 字符串
     */
    public String create(Long userId, String role, String nickname) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(userId.toString())
                .claim("role", role)
                .claim("nickname", nickname)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .signWith(key)
                .compact();
    }

    /**
     * 校验并解析令牌。
     *
     * @param token 去掉 Bearer 前缀的原始 JWT 字符串
     * @return 解析后的 Claims（含 subject、role、nickname 等声明）
     * @throws io.jsonwebtoken.JwtException 签名无效、令牌过期等校验失败时抛出
     */
    public Claims parse(String token) {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    }

    /**
     * 获取令牌有效期秒数，供登录响应返回前端（前端据此安排静默刷新）。
     *
     * @return 有效期秒数
     */
    public long expiresInSeconds() {
        return ttl.toSeconds();
    }
}
