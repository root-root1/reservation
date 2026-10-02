package com.social.seat_reservation.service;

import com.social.seat_reservation.config.ReservationProperties;
import com.social.seat_reservation.domain.exception.*;
import com.social.seat_reservation.domain.model.IdempotencyKey;
import com.social.seat_reservation.domain.model.Reservation;
import com.social.seat_reservation.domain.model.Show;
import com.social.seat_reservation.observability.ReservationMetrics;
import com.social.seat_reservation.repository.IdempotencyKeyRepository;
import com.social.seat_reservation.repository.ReservationRepository;
import com.social.seat_reservation.repository.SeatRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ReservationService {

    private final ShowService showService;
    private final SeatRepository seatRepository;
    private final ReservationRepository reservationRepository;
    private final IdempotencyKeyRepository idempotencyKeyRepository;
    private final ReservationMetrics metrics;
    private final ReservationProperties properties;
    private final Clock clock;

    @Transactional
    public Reservation reserve(String userId, UUID showPublicId, List<String> labels, String idempotencyKey) {
        Show show = showService.requireByPublicId(showPublicId);
        Instant now = clock.instant();
        String fingerprint = fingerprint(showPublicId, labels);

        Optional<IdempotencyKey> existing =
                idempotencyKeyRepository.findByUserIdAndIdempotencyKey(userId, idempotencyKey);
        if (existing.isPresent()) {
            return replayOrConflict(existing.get(), fingerprint);
        }

        List<Long> seatIds = resolveSeatIds(show.getId(), labels);

        enforcePerUserLimit(show.getId(), userId, seatIds.size(), show.getPerUserLimit(), now);

        Instant holdExpiresAt = now.plusSeconds(properties.holdTtlSeconds());
        Reservation reservation = reservationRepository.saveAndFlush(new Reservation(
                UUID.randomUUID(),
                show.getId(),
                userId,
                show.amountFor(seatIds.size()),
                holdExpiresAt));

        int keyRows = idempotencyKeyRepository.insertIfAbsent(
                userId, idempotencyKey, fingerprint, reservation.getId());
        if (keyRows == 0) {
            throw new IdempotentInFlightException();
        }

        int claimed = seatRepository.claimSeats(
                show.getId(), seatIds, userId, reservation.getId(), holdExpiresAt, now);
        if (claimed != seatIds.size()) {
            throw new SeatTakenException();
        }

        metrics.confirmed();
        return reservation;
    }

    @Transactional
    public ReservationDetail confirm(String userId, UUID reservationPublicId) {
        Reservation reservation = requireOwned(userId, reservationPublicId);
        Instant now = clock.instant();

        int reservationRows = reservationRepository.confirmOwnedHold(reservation.getId(), userId, now);
        if (reservationRows == 0) {
            throw new HoldExpiredException();
        }
        seatRepository.confirmSeats(reservation.getId(), userId);

        Reservation confirmed = reservationRepository.findById(reservation.getId())
                .orElseThrow(ReservationNotFoundException::new);
        return detail(confirmed);
    }

    @Transactional(readOnly = true)
    public ReservationDetail detail(Reservation reservation) {
        return new ReservationDetail(
                reservation,
                showService.requireById(reservation.getShowId()).getPublicId(),
                seatRepository.findLabelsByReservationId(reservation.getId()));
    }

    @Transactional
    public void cancel(String userId, UUID reservationPublicId) {
        Reservation reservation = requireOwned(userId, reservationPublicId);

        seatRepository.releaseSeats(reservation.getId(), userId);
        reservationRepository.cancelOwnedHold(reservation.getId(), userId);
    }

    @Transactional(readOnly = true)
    public Reservation requireOwned(String userId, UUID reservationPublicId) {
        Reservation reservation = reservationRepository.findByPublicId(reservationPublicId)
                .orElseThrow(ReservationNotFoundException::new);

        if (!reservation.isOwnedBy(userId)) {
            throw new NotOwnerException();
        }
        return reservation;
    }

    /**
     * SHA-256 hex over the show id plus the SORTED seat labels, so that
     * {@code ["A12","A13"]} and {@code ["A13","A12"]} are the same request and only a
     * genuinely different body trips {@code IdempotencyConflictException}.
     */
    private String fingerprint(UUID showPublicId, List<String> labels) {
        String canonical = showPublicId + "|" + String.join(",", labels.stream().sorted().toList());
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required but unavailable", e);
        }
    }

    /**
     * Fingerprint matches -> load and return the original reservation (exactly-once).
     * Fingerprint differs -> IdempotencyConflictException (409).
     */
    private Reservation replayOrConflict(IdempotencyKey existing, String fingerprint) {
        if (!existing.matches(fingerprint)) {
            throw new IdempotencyConflictException();
        }
        if (existing.getReservationId() == null) {
            throw new IdempotentInFlightException();
        }
        metrics.replayed();
        return reservationRepository.findById(existing.getReservationId())
                .orElseThrow(ReservationNotFoundException::new);
    }

    /**
     * countActiveSeatsForUser + requested > limit -> PerUserLimitExceededException.
     * Must run inside the reserve transaction; a count taken outside it is the same
     * read-then-write race the seat claim exists to avoid.
     */
    private void enforcePerUserLimit(Long showId, String userId, int requested, short limit, Instant now) {
        long held = seatRepository.countActiveSeatsForUser(showId, userId, now);
        if (held + requested > limit) {
            throw new PerUserLimitExceededException();
        }
    }

    /**
     * Labels -> seat ids, already ordered ascending by the query. That ordering is the
     * deadlock protection for multi-seat requests, so it must not be re-sorted or
     * reordered downstream. Fewer ids than labels means an unknown label -> 404.
     */
    private List<Long> resolveSeatIds(Long showId, List<String> labels) {
        List<Long> seatIds = seatRepository.findSeatIdsByShowIdAndLabels(showId, labels);
        if (seatIds.size() != labels.size()) {
            throw new SeatNotFoundException();
        }
        return seatIds;
    }
}
