package com.social.seat_reservation.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.reservation")
public record ReservationProperties(int perUserLimit, long holdTtlSeconds) {}