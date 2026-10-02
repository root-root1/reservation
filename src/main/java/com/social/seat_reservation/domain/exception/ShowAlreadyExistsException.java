package com.social.seat_reservation.domain.exception;

import com.social.seat_reservation.domain.enums.DeclineReason;

public class ShowAlreadyExistsException extends DomainException {

    public ShowAlreadyExistsException(String name) {
        super(DeclineReason.SHOW_ALREADY_EXISTS, "a show named '" + name + "' already exists");
    }
}
