package com.social.seat_reservation.domain.exception;

import com.social.seat_reservation.domain.enums.DeclineReason;

public class SeatNotFoundException extends DomainException {

    public SeatNotFoundException() {
        super(DeclineReason.SEAT_NOT_FOUND, "one or more seat labels are unknown for this show");
    }
}
