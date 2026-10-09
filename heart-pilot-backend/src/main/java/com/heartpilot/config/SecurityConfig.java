package com.heartpilot.config;

import com.heartpilot.security.*;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.*;

/**
 * Spring Security 全局安全配置类。 负责三件事：密码编码器、CORS 跨域策略、HTTP 安全过滤链（鉴权规则 + JWT 过滤器 + 限流过滤器）。
 * 采用无状态（STATELESS）会话策略，所有认证依赖 JWT 令牌，不使用 HttpSession。 @EnableMethodSecurity
 * 开启方法级权限注解（如 @PreAuthorize），可在 Controller/Service 上细粒度控制。
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {
    /**
     * 密码编码器 Bean，使用 BCrypt 算法。 BCrypt 自带盐值和工作因子，是 Spring Security 推荐的密码哈希方案。 用户注册时加密存储，登录时通过
     * matches 比对。
     */
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * CORS 跨域配置 Bean。 从配置项 app.cors.allowed-origins 读取允许的前端域名（逗号分隔）， 允许全部常用 HTTP
     * 方法和请求头，开启携带凭证（Cookie/Authorization）。 额外暴露 Content-Disposition（文件下载文件名）和
     * X-RateLimit-Remaining（限流剩余次数）响应头， 否则浏览器侧 JavaScript 无法读取这些头。
     *
     * @param origins 配置文件中逗号分隔的允许源列表
     * @return 注册到所有路径 /** 的 CORS 配置源
     */
    @Bean
    CorsConfigurationSource corsConfigurationSource(
            @Value("${app.cors.allowed-origins}") String origins) {
        CorsConfiguration c = new CorsConfiguration();
        // 拆分并去除每个源的首尾空格，支持配置文件中写 "http://a.com, http://b.com"
        c.setAllowedOrigins(Arrays.stream(origins.split(",")).map(String::trim).toList());
        c.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        c.setAllowedHeaders(List.of("*"));
        c.setExposedHeaders(List.of("Content-Disposition", "X-RateLimit-Remaining"));
        c.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource s = new UrlBasedCorsConfigurationSource();
        s.registerCorsConfiguration("/**", c);
        return s;
    }

    /**
     * 核心安全过滤链。 过滤顺序：JWT 认证过滤器 → 限流过滤器 → 授权规则。 放行路径：ASYNC/ERROR 派发、登录注册 /auth/**、健康检查
     * /health、Swagger 文档；其余接口必须认证。 ASYNC/ERROR 派发放行是 SSE（SseEmitter）异步接口正常运行的前提：接口返回后容器会发起
     * 异步派发并再次经过本过滤链，此时认证上下文在线程间不传递，若再次执行 anyRequest().authenticated() 会误判为匿名并中断 SSE 流。
     *
     * @param http Spring Security 的 HttpSecurity 构建器
     * @param jwt JWT 认证过滤器，从请求头解析令牌并设置 SecurityContext
     * @param rate 限流过滤器，在认证通过后按用户维度限流
     * @return 构建完成的 SecurityFilterChain
     * @throws Exception 配置构建失败时抛出
     */
    @Bean
    SecurityFilterChain filterChain(
            HttpSecurity http, JwtAuthenticationFilter jwt, RateLimitFilter rate) throws Exception {
        return http.csrf(x -> x.disable())
                // 前后端分离架构使用 JWT，无需 CSRF 防护（CSRF 主要针对 Cookie 会话）
                .cors(x -> {})
                // 无状态会话：不创建、不使用 HttpSession，每次请求靠 JWT 认证
                .sessionManagement(x -> x.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // 未认证请求统一返回 401，而非重定向到登录页（前后端分离不需要页面跳转）
                .exceptionHandling(
                        x ->
                                x.authenticationEntryPoint(
                                        (request, response, exception) ->
                                                response.sendError(
                                                        HttpServletResponse.SC_UNAUTHORIZED,
                                                        "Unauthorized")))
                .authorizeHttpRequests(
                        x ->
                                x.dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR)
                                        .permitAll()
                                        // 放行 ASYNC/ERROR 派发：SSE（SseEmitter）等异步接口在
                                        // Servlet 异步派发时会再次进入本过滤器链，此时无需重新授权，
                                        // 首次 REQUEST 派发时已做过认证与授权检查。
                                        .requestMatchers(
                                                "/auth/**",
                                                "/health",
                                                "/actuator/health",
                                                "/v3/api-docs/**",
                                                "/swagger-ui/**",
                                                "/swagger-ui.html")
                                        .permitAll()
                                        // GET /public/** 放行，用于无需登录的公开资源访问
                                        .requestMatchers(HttpMethod.GET, "/public/**")
                                        .permitAll()
                                        .anyRequest()
                                        .authenticated())
                // JWT 过滤器放在 UsernamePasswordAuthenticationFilter 之前，优先解析令牌
                .addFilterBefore(jwt, UsernamePasswordAuthenticationFilter.class)
                // 限流过滤器放在 JWT 之后，确保能拿到当前用户 ID 进行维度限流
                .addFilterAfter(rate, JwtAuthenticationFilter.class)
                .build();
    }
}
