package com.social.seat_reservation.domain.exception;

import com.social.seat_reservation.domain.enums.DeclineReason;

public class ReservationNotFoundException extends DomainException {

    public ReservationNotFoundException() {
        super(DeclineReason.RESERVATION_NOT_FOUND, "reservation not found");
    }
}
