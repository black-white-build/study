package com.heartpilot.security;

import io.jsonwebtoken.Claims;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * JWT 认证过滤器。
 * 过滤顺序：位于 UsernamePasswordAuthenticationFilter 之前（见 SecurityConfig），
 * 负责从 Authorization: Bearer <token> 请求头解析令牌并写入 SecurityContext，
 * 后续限流过滤器与授权规则据此识别当前用户。
 * 令牌无效或缺失时不抛异常，直接放行交由后续授权规则返回 401，保证过滤器只做认证不做拦截。
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private final JwtService jwt;

    public JwtAuthenticationFilter(JwtService jwt) {
        this.jwt = jwt;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String value = req.getHeader(HttpHeaders.AUTHORIZATION);
        // 仅处理带 Bearer 前缀的请求；SecurityContext 已有认证信息时不重复设置
        if (value != null
                && value.startsWith("Bearer ")
                && SecurityContextHolder.getContext().getAuthentication() == null) {
            try {
                // 去掉 "Bearer " 前缀后解析令牌，校验签名与过期时间
                Claims claims = jwt.parse(value.substring(7));
                String role = claims.get("role", String.class);
                // 主体为用户 ID，权限由角色转换而来（ROLE_ 前缀由 Spring Security 约定）
                var auth =
                        new UsernamePasswordAuthenticationToken(
                                claims.getSubject(),
                                null,
                                List.of(new SimpleGrantedAuthority("ROLE_" + role)));
                // 昵称放入 details，供业务层展示使用
                auth.setDetails(claims.get("nickname", String.class));
                SecurityContextHolder.getContext().setAuthentication(auth);
            } catch (Exception ignored) {
                // 令牌过期/签名错误等异常静默忽略：不写入认证信息，由授权规则最终返回 401
            }
        }
        chain.doFilter(req, res);
    }
}
