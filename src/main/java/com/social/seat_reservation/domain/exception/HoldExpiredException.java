package com.social.seat_reservation.domain.exception;

import com.social.seat_reservation.domain.enums.DeclineReason;

public class HoldExpiredException extends DomainException {

    public HoldExpiredException() {
        super(DeclineReason.HOLD_EXPIRED, "the hold has expired or is no longer active");
    }
}
