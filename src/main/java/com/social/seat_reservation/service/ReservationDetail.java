package com.social.seat_reservation.service;

import com.social.seat_reservation.domain.model.Reservation;

import java.util.List;
import java.util.UUID;

public record ReservationDetail(Reservation reservation, UUID showPublicId, List<String> seatLabels) {
}
