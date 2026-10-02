package com.social.seat_reservation.domain.exception;

import com.social.seat_reservation.domain.enums.DeclineReason;

public class NotOwnerException extends DomainException {

    public NotOwnerException() {
        super(DeclineReason.NOT_OWNER, "reservation belongs to another user");
    }
}
