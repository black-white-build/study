package com.heartpilot.module.auth.service;

import com.heartpilot.module.auth.dto.AuthDtos;

public interface AuthService {
    AuthDtos.SessionResponse register(String username, String password, String nickname);

    AuthDtos.SessionResponse login(String username, String password);
}
