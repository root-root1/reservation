package com.social.seat_reservation.domain.exception;

import com.social.seat_reservation.domain.enums.DeclineReason;

public class PerUserLimitExceededException extends DomainException {

    public PerUserLimitExceededException() {
        super(DeclineReason.PER_USER_LIMIT, "request would exceed the per-user seat limit");
    }
}
