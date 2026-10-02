package com.social.seat_reservation.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.auth")
public record AuthProperties(String jwtSecret,
                             String jwtIssuer,
                             long tokenTtlMinutes,
                             String adminUsername,
                             String adminPassword) {
}
