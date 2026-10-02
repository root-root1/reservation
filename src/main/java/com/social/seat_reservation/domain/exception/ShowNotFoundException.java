package com.social.seat_reservation.domain.exception;

import com.social.seat_reservation.domain.enums.DeclineReason;

public class ShowNotFoundException extends DomainException {

    public ShowNotFoundException() {
        super(DeclineReason.SHOW_NOT_FOUND, "show not found");
    }
}
