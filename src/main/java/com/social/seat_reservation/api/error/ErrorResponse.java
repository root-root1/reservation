package com.social.seat_reservation.api.error;

import com.social.seat_reservation.domain.enums.DeclineReason;

public record ErrorResponse(String reason, String message, String requestId) {

    public static ErrorResponse of(DeclineReason reason, String message, String requestId) {
        return new ErrorResponse(reason.getWireValue(), message, requestId);
    }
}
