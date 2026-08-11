package com.heartpilot.module.auth.service.impl;

import com.heartpilot.common.exception.ApiException;
import com.heartpilot.module.auth.dto.AuthDtos;
import com.heartpilot.module.user.dto.UserDtos;
import com.heartpilot.module.user.entity.AppUser;
import com.heartpilot.module.user.repository.AppUserRepository;
import com.heartpilot.security.JwtService;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {
    private final AppUserRepository users;
    private final PasswordEncoder encoder;
    private final JwtService jwt;

    public AuthService(AppUserRepository users, PasswordEncoder encoder, JwtService jwt) {
        this.users = users;
        this.encoder = encoder;
        this.jwt = jwt;
    }

    @Transactional
    public AuthDtos.SessionResponse register(String username, String password, String nickname) {
        String normalized = username.trim().toLowerCase();
        if (users.existsByUsernameIgnoreCase(normalized)) {
            throw new ApiException(HttpStatus.CONFLICT, "USERNAME_EXISTS", "用户名已存在");
        }
        AppUser user = new AppUser();
        user.setUsername(normalized);
        user.setPasswordHash(encoder.encode(password));
        user.setNickname(nickname == null || nickname.isBlank() ? normalized : nickname.trim());
        return session(users.save(user));
    }

    public AuthDtos.SessionResponse login(String username, String password) {
        AppUser user =
                users.findByUsernameIgnoreCase(username.trim())
                        .orElseThrow(() -> invalidCredentials());
        if (!user.isEnabled() || !encoder.matches(password, user.getPasswordHash()))
            throw invalidCredentials();
        return session(user);
    }

    private AuthDtos.SessionResponse session(AppUser user) {
        return new AuthDtos.SessionResponse(
                jwt.create(user.getId(), user.getRole(), user.getNickname()),
                jwt.expiresInSeconds(),
                UserDtos.UserResponse.from(user));
    }

    private ApiException invalidCredentials() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "用户名或密码错误");
    }
}
