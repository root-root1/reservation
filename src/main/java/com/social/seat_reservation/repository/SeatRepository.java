package com.social.seat_reservation.repository;

import com.social.seat_reservation.domain.model.Seat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface SeatRepository extends JpaRepository<Seat, Long> {

    @Query(nativeQuery = true, value = """
            SELECT id
              FROM seats
             WHERE show_id = :showId
               AND label IN (:labels)
             ORDER BY id ASC
            """)
    List<Long> findSeatIdsByShowIdAndLabels(@Param("showId") Long showId,
                                            @Param("labels") Collection<String> labels);

    @Modifying(clearAutomatically = true)
    @Query(nativeQuery = true, value = """
            UPDATE seats
               SET status          = 'HELD',
                   claimed_by      = :userId,
                   hold_expires_at = :holdExpiresAt,
                   reservation_id  = :reservationId
             WHERE show_id = :showId
               AND id IN (:seatIds)
               AND (status = 'AVAILABLE'
                    OR (status = 'HELD' AND hold_expires_at <= :now))
            """)
    int claimSeats(@Param("showId") Long showId,
                   @Param("seatIds") Collection<Long> seatIds,
                   @Param("userId") String userId,
                   @Param("reservationId") Long reservationId,
                   @Param("holdExpiresAt") Instant holdExpiresAt,
                   @Param("now") Instant now);

    List<Seat> findByShowIdOrderByIdAsc(Long showId);

    @Query(nativeQuery = true, value = """
            SELECT COUNT(*)
              FROM seats
             WHERE show_id    = :showId
               AND claimed_by = :userId
               AND (status = 'CONFIRMED'
                    OR (status = 'HELD' AND hold_expires_at > :now))
            """)
    long countActiveSeatsForUser(@Param("showId") Long showId,
                                 @Param("userId") String userId,
                                 @Param("now") Instant now);

    @Query(nativeQuery = true, value = """
            SELECT COUNT(*) FILTER (WHERE status = 'AVAILABLE'
                                       OR (status = 'HELD' AND hold_expires_at <= :now)) AS "availableCount",
                   COUNT(*) FILTER (WHERE status = 'HELD'
                                      AND hold_expires_at > :now)                        AS "heldCount",
                   COUNT(*) FILTER (WHERE status = 'CONFIRMED')                          AS "confirmedCount",
                   COUNT(*)                                                              AS "totalCount"
              FROM seats
             WHERE show_id = :showId
            """)
    SeatCounts countByEffectiveStatus(@Param("showId") Long showId,
                                      @Param("now") Instant now);

    @Modifying(clearAutomatically = true)
    @Query(nativeQuery = true, value = """
            UPDATE seats
               SET status          = 'CONFIRMED',
                   hold_expires_at = NULL
             WHERE reservation_id = :reservationId
               AND claimed_by     = :userId
               AND status         = 'HELD'
            """)
    int confirmSeats(@Param("reservationId") Long reservationId,
                     @Param("userId") String userId);

    @Modifying(clearAutomatically = true)
    @Query(nativeQuery = true, value = """
            UPDATE seats
               SET status          = 'AVAILABLE',
                   claimed_by      = NULL,
                   hold_expires_at = NULL,
                   reservation_id  = NULL
             WHERE reservation_id = :reservationId
               AND claimed_by     = :userId
               AND status         = 'HELD'
            """)
    int releaseSeats(@Param("reservationId") Long reservationId,
                     @Param("userId") String userId);

    @Modifying(clearAutomatically = true)
    @Query(nativeQuery = true, value = """
            UPDATE seats
               SET status          = 'AVAILABLE',
                   claimed_by      = NULL,
                   hold_expires_at = NULL,
                   reservation_id  = NULL
             WHERE status          = 'HELD'
               AND hold_expires_at <= :now
            """)
    int releaseExpiredHolds(@Param("now") Instant now);

    @Modifying
    @Query(nativeQuery = true, value = """
            INSERT INTO seats (show_id, label)
            SELECT :showId, unnest(CAST(:labels AS text[]))
            """)
    int bulkInsertSeats(@Param("showId") Long showId,
                        @Param("labels") String labels);

    @Query("SELECT s.label FROM Seat s WHERE s.reservationId = :reservationId ORDER BY s.id ASC")
    List<String> findLabelsByReservationId(@Param("reservationId") Long reservationId);
}
