package com.social.seat_reservation.service;

import com.social.seat_reservation.repository.ReservationRepository;
import com.social.seat_reservation.repository.SeatRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/**
 * Housekeeping only. Correctness does not depend on this running: every query that
 * reads seat state already treats a lapsed hold as available, so a seat is re-bookable
 * the instant {@code hold_expires_at} passes. This sweep just settles the stored rows
 * so {@code status} stops lying and the partial index stays small.
 *
 * <p>Safe to run on several instances at once — both statements are guarded on
 * {@code status = 'HELD' AND expiry <= now}, so a second sweeper affects zero rows.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HoldExpiryService {

    private final SeatRepository seatRepository;
    private final ReservationRepository reservationRepository;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${app.reservation.sweep-interval-ms:30000}")
    @Transactional
    public int sweepExpiredHolds() {
        Instant now = clock.instant();

        int seatsReleased = seatRepository.releaseExpiredHolds(now);
        int reservationsExpired = reservationRepository.markExpired(now);

        if (seatsReleased > 0 || reservationsExpired > 0) {
            log.info("hold sweep released seats={} expired reservations={}", seatsReleased, reservationsExpired);
        }
        return seatsReleased;
    }
}
