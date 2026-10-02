package com.social.seat_reservation.api.dto.response;

import com.social.seat_reservation.domain.model.Reservation;
import com.social.seat_reservation.service.ReservationDetail;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ReservationResponse(
        UUID reservationId,
        UUID showId,
        String userId,
        List<String> seats,
        long amountPaise,
        String status,
        Instant expiresAt
) {

    public static ReservationResponse of(Reservation reservation, UUID showPublicId, List<String> seats, Instant now) {
        return new ReservationResponse(
                reservation.getPublicId(),
                showPublicId,
                reservation.getUserId(),
                seats,
                reservation.getAmountPaise(),
                reservation.effectiveStatus(now).name().toLowerCase(),
                reservation.getExpiresAt());
    }

    public static ReservationResponse of(ReservationDetail detail, Instant now) {
        return of(detail.reservation(), detail.showPublicId(), detail.seatLabels(), now);
    }
}
