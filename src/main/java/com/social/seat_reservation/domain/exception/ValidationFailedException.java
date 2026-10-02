package com.social.seat_reservation.domain.exception;

import com.social.seat_reservation.domain.enums.DeclineReason;

public class ValidationFailedException extends DomainException {

    public ValidationFailedException(String message) {
        super(DeclineReason.VALIDATION_FAILED, message);
    }
}
