package com.social.seat_reservation.domain.exception;

import com.social.seat_reservation.domain.enums.DeclineReason;

public class UnauthenticatedException extends DomainException {

    public UnauthenticatedException(String message) {
        super(DeclineReason.UNAUTHENTICATED, message);
    }
}
