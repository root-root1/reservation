package com.social.seat_reservation.domain.exception;

import com.social.seat_reservation.domain.enums.DeclineReason;

public class IdempotentInFlightException extends DomainException {

    public IdempotentInFlightException() {
        super(DeclineReason.IDEMPOTENT_IN_FLIGHT,
                "an identical request with this idempotency key is still in flight; retry");
    }
}
