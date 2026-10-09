package com.heartpilot.module.auth.controller;

import com.heartpilot.module.auth.dto.AuthDtos;
import com.heartpilot.module.auth.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证控制器，路径前缀 /auth。 对外提供注册与登录两个免登录接口，认证成功后返回 JWT 访问令牌与用户信息。 这两个接口在 SecurityConfig 中被配置为 permitAll
 * 放行，无需携带令牌即可访问。
 */
@RestController
@RequestMapping("/auth")
public class AuthController {
    private final AuthService service;

    public AuthController(AuthService service) {
        this.service = service;
    }

    /**
     * POST /auth/register —— 用户注册。 校验用户名/密码格式（由 @Valid 触发），注册成功后直接签发 JWT 令牌完成自动登录。
     *
     * @param request 注册请求体（用户名、密码、昵称）
     * @return 会话响应（访问令牌、过期时间、用户信息）
     */
    @PostMapping("/register")
    AuthDtos.SessionResponse register(@Valid @RequestBody AuthDtos.RegisterRequest request) {
        return service.register(request.username(), request.password(), request.nickname());
    }

    /**
     * POST /auth/login —— 用户登录。 校验用户名密码，成功后返回 JWT 访问令牌；失败由 Service 层统一返回 401。
     *
     * @param request 登录请求体（用户名、密码）
     * @return 会话响应（访问令牌、过期时间、用户信息）
     */
    @PostMapping("/login")
    AuthDtos.SessionResponse login(@Valid @RequestBody AuthDtos.LoginRequest request) {
        return service.login(request.username(), request.password());
    }
}
