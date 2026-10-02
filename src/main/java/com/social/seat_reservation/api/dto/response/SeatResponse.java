package com.social.seat_reservation.api.dto.response;

import com.social.seat_reservation.domain.enums.SeatStatus;

public record SeatResponse(String label, String status) {

    public static SeatResponse of(String label, SeatStatus status) {
        return new SeatResponse(label, status.name().toLowerCase());
    }
}
