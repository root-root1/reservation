package com.social.seat_reservation.api.dto.response;

import java.util.UUID;

public record TokenResponse(UUID userId, String username, String role, String token) {
}
