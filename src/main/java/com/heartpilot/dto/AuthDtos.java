package com.heartpilot.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class AuthDtos {
    private AuthDtos() {}

    public record RegisterRequest(
            @NotBlank @Size(min = 3, max = 64) String username,
            @NotBlank @Size(min = 8, max = 72) String password,
            @Size(max = 64) String nickname) {}

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {}

    public record SessionResponse(String accessToken, long expiresIn, UserDtos.UserResponse user) {}
}
