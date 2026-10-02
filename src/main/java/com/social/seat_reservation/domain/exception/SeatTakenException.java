package com.social.seat_reservation.domain.exception;

import com.social.seat_reservation.domain.enums.DeclineReason;

public class SeatTakenException extends DomainException {

    public SeatTakenException() {
        super(DeclineReason.SEAT_TAKEN, "one or more seats are no longer available");
    }
}
