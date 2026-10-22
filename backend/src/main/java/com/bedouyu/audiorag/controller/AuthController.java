package com.bedouyu.audiorag.controller;

import com.bedouyu.audiorag.model.dto.*;

import com.bedouyu.audiorag.service.AuthService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class AuthController {
    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/auth/register")
    public Map<String, Object> register(@Valid @RequestBody AuthCredentials credentials) {
        return authService.register(credentials.username(), credentials.password());
    }

    @PostMapping("/auth/login")
    public Map<String, Object> login(@Valid @RequestBody AuthCredentials credentials) {
        return authService.login(credentials.username(), credentials.password());
    }

    @GetMapping("/me")
    public Map<String, Object> me(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return authService.requireUser(authorization);
    }

}
