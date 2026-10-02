package com.social.seat_reservation.service;

import com.social.seat_reservation.config.ReservationProperties;
import com.social.seat_reservation.domain.model.IdempotencyKey;
import com.social.seat_reservation.domain.model.Reservation;
import com.social.seat_reservation.repository.IdempotencyKeyRepository;
import com.social.seat_reservation.repository.ReservationRepository;
import com.social.seat_reservation.repository.SeatRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ReservationService {

    private final ShowService showService;
    private final SeatRepository seatRepository;
    private final ReservationRepository reservationRepository;
    private final IdempotencyKeyRepository idempotencyKeyRepository;
    private final ReservationProperties properties;
    private final Clock clock;

    @Transactional
    public Reservation reserve(String userId, UUID showPublicId, List<String> labels, String idempotencyKey) {
        throw new UnsupportedOperationException();
    }

    @Transactional
    public void cancel(String userId, UUID reservationPublicId) {
        throw new UnsupportedOperationException();
    }

    @Transactional(readOnly = true)
    public Reservation requireOwned(String userId, UUID reservationPublicId) {
        throw new UnsupportedOperationException();
    }

    private String fingerprint(UUID showPublicId, List<String> labels) {
        throw new UnsupportedOperationException();
    }

    private Reservation replayOrConflict(IdempotencyKey existing, String fingerprint) {
        throw new UnsupportedOperationException();
    }

    private void enforcePerUserLimit(Long showId, String userId, int requested, short limit, Instant now) {
        throw new UnsupportedOperationException();
    }

    private List<Long> resolveSeatIds(Long showId, List<String> labels) {
        throw new UnsupportedOperationException();
    }
}
