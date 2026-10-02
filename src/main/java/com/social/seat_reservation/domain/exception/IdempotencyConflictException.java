package com.social.seat_reservation.domain.exception;

import com.social.seat_reservation.domain.enums.DeclineReason;

public class IdempotencyConflictException extends DomainException {

    public IdempotencyConflictException() {
        super(DeclineReason.IDEMPOTENCY_CONFLICT, "idempotency key already used with a different request");
    }
}
