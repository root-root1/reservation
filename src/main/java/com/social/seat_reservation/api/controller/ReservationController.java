package com.social.seat_reservation.api.controller;

import com.social.seat_reservation.api.dto.request.ReserveRequest;
import com.social.seat_reservation.api.dto.response.ReservationResponse;
import com.social.seat_reservation.domain.exception.ValidationFailedException;
import com.social.seat_reservation.domain.model.Reservation;
import com.social.seat_reservation.security.AuthenticatedUser;
import com.social.seat_reservation.service.ReservationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class ReservationController {

    private static final String IDEMPOTENCY_HEADER = "Idempotency-Key";

    private final ReservationService reservationService;
    private final Clock clock;

    @PostMapping("/shows/{showId}/reserve")
    @ResponseStatus(HttpStatus.CREATED)
    public ReservationResponse reserve(@AuthenticationPrincipal AuthenticatedUser user,
                                       @PathVariable UUID showId,
                                       @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String headerKey,
                                       @Valid @RequestBody ReserveRequest request) {

        String idempotencyKey = resolveIdempotencyKey(headerKey, request.idempotencyKey());

        Reservation reservation =
                reservationService.reserve(user.userId(), showId, request.seats(), idempotencyKey);

        return ReservationResponse.of(reservation, showId, request.seats(), clock.instant());
    }

    @PostMapping("/reservations/{reservationId}/confirm")
    public ReservationResponse confirm(@AuthenticationPrincipal AuthenticatedUser user,
                                       @PathVariable UUID reservationId) {
        return ReservationResponse.of(reservationService.confirm(user.userId(), reservationId), clock.instant());
    }

    @PostMapping("/reservations/{reservationId}/cancel")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(@AuthenticationPrincipal AuthenticatedUser user,
                       @PathVariable UUID reservationId) {
        reservationService.cancel(user.userId(), reservationId);
    }

    private String resolveIdempotencyKey(String headerKey, String bodyKey) {
        String key = headerKey != null && !headerKey.isBlank() ? headerKey : bodyKey;
        if (key == null || key.isBlank()) {
            throw new ValidationFailedException("an idempotency_key header or body field is required");
        }
        return key;
    }
}
