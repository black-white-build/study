package com.heartpilot.module.auth.controller;

import com.heartpilot.module.auth.dto.AuthDtos;
import com.heartpilot.module.auth.service.impl.AuthService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
public class AuthController {
    private final AuthService service;

    public AuthController(AuthService service) {
        this.service = service;
    }

    @PostMapping("/register")
    AuthDtos.SessionResponse register(@Valid @RequestBody AuthDtos.RegisterRequest request) {
        return service.register(request.username(), request.password(), request.nickname());
    }

    @PostMapping("/login")
    AuthDtos.SessionResponse login(@Valid @RequestBody AuthDtos.LoginRequest request) {
        return service.login(request.username(), request.password());
    }
}
