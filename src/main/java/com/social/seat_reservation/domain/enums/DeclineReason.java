package com.social.seat_reservation.domain.enums;

import lombok.Getter;

@Getter
public enum DeclineReason {

    SEAT_TAKEN(409, "seat_taken"),
    SHOW_ALREADY_EXISTS(409, "show_already_exists"),
    USER_ALREADY_EXISTS(409, "user_already_exists"),
    PER_USER_LIMIT(409, "per_user_limit"),
    IDEMPOTENCY_CONFLICT(409, "idempotency_conflict"),
    NOT_OWNER(403, "not_owner"),
    SHOW_NOT_FOUND(404, "show_not_found"),
    SEAT_NOT_FOUND(404, "seat_not_found"),
    RESERVATION_NOT_FOUND(404, "reservation_not_found"),
    VALIDATION_FAILED(400, "validation_failed"),
    UNAUTHENTICATED(401, "unauthenticated"),
    REJECTED_OVERLOAD(429, "rejected_overload");

    private final int httpStatus;
    private final String wireValue;

    DeclineReason(int httpStatus, String wireValue) {
        this.httpStatus = httpStatus;
        this.wireValue = wireValue;
    }
}
