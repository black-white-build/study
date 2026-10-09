package com.heartpilot.module.auth.dto;

import com.heartpilot.module.user.dto.UserDtos;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 认证模块相关 DTO 聚合类。 使用私有构造器禁止实例化，内部以 record 形式定义注册/登录请求与会话响应。 */
public final class AuthDtos {
    private AuthDtos() {}

    /**
     * 注册请求体。
     *
     * @param username 登录用户名，3-64 位，非空
     * @param password 登录密码，8-72 位，非空（传输后由服务端 BCrypt 加密存储）
     * @param nickname 展示昵称，最长 64 位，可为空，为空时默认取用户名
     */
    public record RegisterRequest(
            @NotBlank @Size(min = 3, max = 64) String username,
            @NotBlank @Size(min = 8, max = 72) String password,
            @Size(max = 64) String nickname) {}

    /**
     * 登录请求体。
     *
     * @param username 登录用户名，非空
     * @param password 登录密码，非空
     */
    public record LoginRequest(@NotBlank String username, @NotBlank String password) {}

    /**
     * 会话响应体，注册/登录成功后返回。
     *
     * @param accessToken JWT 访问令牌，后续请求放在 Authorization 头中
     * @param expiresIn 令牌有效期（秒）
     * @param user 当前登录用户的基本信息
     */
    public record SessionResponse(String accessToken, long expiresIn, UserDtos.UserResponse user) {}
}
