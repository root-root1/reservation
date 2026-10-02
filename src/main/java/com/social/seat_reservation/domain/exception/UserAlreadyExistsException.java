package com.social.seat_reservation.domain.exception;

import com.social.seat_reservation.domain.enums.DeclineReason;

public class UserAlreadyExistsException extends DomainException {

    public UserAlreadyExistsException(String username) {
        super(DeclineReason.USER_ALREADY_EXISTS, "username '" + username + "' is taken");
    }
}
