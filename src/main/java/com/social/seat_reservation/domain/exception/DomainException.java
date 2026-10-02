package com.social.seat_reservation.domain.exception;

import com.social.seat_reservation.domain.enums.DeclineReason;
import lombok.Getter;

@Getter
public abstract class DomainException extends RuntimeException {
    private final DeclineReason reason;

    protected DomainException(DeclineReason reason, String message) {
        super(message, null, false, false);
        this.reason = reason;
    }
}
