package com.heartpilot.module.auth.service.impl;

import com.heartpilot.common.exception.ApiException;
import com.heartpilot.module.auth.dto.AuthDtos;
import com.heartpilot.module.auth.service.AuthService;
import com.heartpilot.module.user.dto.UserDtos;
import com.heartpilot.module.user.entity.AppUser;
import com.heartpilot.module.user.repository.AppUserRepository;
import com.heartpilot.security.JwtService;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 认证服务实现。
 * 负责完整的用户认证流程：
 * - 注册：用户名规范化（去空格、转小写）→ 唯一性校验 → BCrypt 加密密码 → 落库 → 签发 JWT
 * - 登录：按用户名查询 → 校验账号是否启用 → BCrypt 比对密码 → 签发 JWT
 * 密码使用 BCryptPasswordEncoder 加密存储，服务端不保存明文；
 * JWT 令牌由 JwtService 统一签发，载荷中携带用户 ID、角色与昵称，后续请求无状态校验。
 */
@Service
public class AuthServiceImpl implements AuthService {
    /** 用户仓库，按用户名查询与唯一性判断 */
    private final AppUserRepository users;
    /** 密码编码器（BCrypt），注册时加密、登录时比对 */
    private final PasswordEncoder encoder;
    /** JWT 服务，负责生成访问令牌并告知有效期 */
    private final JwtService jwt;

    public AuthServiceImpl(AppUserRepository users, PasswordEncoder encoder, JwtService jwt) {
        this.users = users;
        this.encoder = encoder;
        this.jwt = jwt;
    }

    /**
     * 注册新用户并直接登录（注册成功即签发令牌）。
     * 用户名统一转小写存储，保证登录时大小写不敏感。
     */
    @Transactional
    @Override
    public AuthDtos.SessionResponse register(String username, String password, String nickname) {
        // 规范化用户名：去除首尾空格并转小写，避免 "Alice" 与 "alice" 被当成两个账号
        String normalized = username.trim().toLowerCase();
        // 用户名已存在则冲突（409），由数据库唯一索引兜底并发场景
        if (users.existsByUsernameIgnoreCase(normalized)) {
            throw new ApiException(HttpStatus.CONFLICT, "USERNAME_EXISTS", "用户名已存在");
        }
        AppUser user = new AppUser();
        user.setUsername(normalized);
        // BCrypt 加密：算法内部自动加盐，即使相同密码每次哈希结果也不同，禁止明文存储
        user.setPasswordHash(encoder.encode(password));
        // 昵称为空时默认使用规范化后的用户名
        user.setNickname(nickname == null || nickname.isBlank() ? normalized : nickname.trim());
        // 落库后签发 JWT 会话
        return session(users.save(user));
    }

    /**
     * 登录校验。
     * 出于安全考虑，用户名不存在、账号被禁用、密码错误三种情况统一返回相同的 401 提示，
     * 避免攻击者通过差异化响应探测哪些用户名已注册。
     */
    @Override
    public AuthDtos.SessionResponse login(String username, String password) {
        // 用户名大小写不敏感查询，查不到直接按凭证错误处理
        AppUser user =
                users.findByUsernameIgnoreCase(username.trim())
                        .orElseThrow(() -> invalidCredentials());
        // 账号未启用或密码不匹配都视为凭证错误（不暴露具体原因）
        if (!user.isEnabled() || !encoder.matches(password, user.getPasswordHash()))
            throw invalidCredentials();
        return session(user);
    }

    /**
     * 构造会话响应：根据用户信息签发 JWT 访问令牌。
     * 令牌载荷包含用户 ID、角色、昵称，过期时间由 JwtService 统一配置。
     */
    private AuthDtos.SessionResponse session(AppUser user) {
        return new AuthDtos.SessionResponse(
                jwt.create(user.getId(), user.getRole(), user.getNickname()),
                jwt.expiresInSeconds(),
                UserDtos.UserResponse.from(user));
    }

    /** 统一构造 401 凭证错误异常，避免重复写相同文案 */
    private ApiException invalidCredentials() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "用户名或密码错误");
    }
}
