package com.social.seat_reservation.api.dto.request;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public record ReserveRequest(

        @NotEmpty
        @Size(max = 16)
        List<@jakarta.validation.constraints.NotBlank @Size(max = 16) String> seats,

        @Size(max = 128)
        String idempotencyKey
) {
}
