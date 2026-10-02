package com.social.seat_reservation.api.dto.request;

import jakarta.validation.constraints.NotBlank;

public record LoginRequest(

        @NotBlank
        String username,

        @NotBlank
        String password
) {
}
