package com.social.seat_reservation.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.List;

public record CreateShowRequest(

        @NotBlank
        @Size(max = 128)
        String name,

        @NotEmpty
        List<@NotBlank @Size(max = 16) String> seats,

        @PositiveOrZero
        long pricePaise,

        @Positive
        Integer perUserLimit
) {
}
