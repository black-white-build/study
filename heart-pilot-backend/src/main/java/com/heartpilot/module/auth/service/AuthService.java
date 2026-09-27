package com.heartpilot.module.auth.service;

import com.heartpilot.module.auth.dto.AuthDtos;

/**
 * 认证服务接口。
 * 定义用户注册与登录的业务能力，成功后均返回包含 JWT 令牌的会话响应。
 */
public interface AuthService {
    /**
     * 注册新用户：校验用户名唯一、BCrypt 加密密码、落库后签发 JWT。
     * @param username 登录用户名
     * @param password 明文密码（服务端加密存储）
     * @param nickname 展示昵称，可为空
     * @return 会话响应（令牌 + 用户信息）
     */
    AuthDtos.SessionResponse register(String username, String password, String nickname);

    /**
     * 登录校验：按用户名查询用户并比对密码哈希，通过后签发 JWT。
     * @param username 登录用户名
     * @param password 明文密码
     * @return 会话响应（令牌 + 用户信息）
     */
    AuthDtos.SessionResponse login(String username, String password);
}
