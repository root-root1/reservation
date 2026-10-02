package com.social.seat_reservation.api.controller;

import com.social.seat_reservation.api.dto.request.LoginRequest;
import com.social.seat_reservation.api.dto.request.RegisterRequest;
import com.social.seat_reservation.api.dto.response.TokenResponse;
import com.social.seat_reservation.domain.model.User;
import com.social.seat_reservation.security.Role;
import com.social.seat_reservation.security.TokenService;
import com.social.seat_reservation.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserService userService;
    private final TokenService tokenService;

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public TokenResponse register(@Valid @RequestBody RegisterRequest request) {
        return toResponse(userService.register(request.username(), request.password(), Role.USER));
    }

    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest request) {
        return toResponse(userService.authenticate(request.username(), request.password()));
    }

    private TokenResponse toResponse(User user) {
        return new TokenResponse(
                user.getPublicId(),
                user.getUsername(),
                user.getRole().name(),
                tokenService.issue(user));
    }
}
