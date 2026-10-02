package com.social.seat_reservation.repository;

import com.social.seat_reservation.domain.model.Reservation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {

    Optional<Reservation> findByPublicId(UUID publicId);

    @Modifying(clearAutomatically = true)
    @Query(nativeQuery = true, value = """
            UPDATE reservations
               SET status     = 'CANCELLED',
                   expires_at = NULL
             WHERE id      = :reservationId
               AND user_id = :userId
               AND status  = 'HELD'
            """)
    int cancelOwnedHold(@Param("reservationId") Long reservationId,
                        @Param("userId") String userId);

    @Modifying(clearAutomatically = true)
    @Query(nativeQuery = true, value = """
            UPDATE reservations
               SET status     = 'CONFIRMED',
                   expires_at = NULL
             WHERE id      = :reservationId
               AND user_id  = :userId
               AND status   = 'HELD'
               AND expires_at > :now
            """)
    int confirmOwnedHold(@Param("reservationId") Long reservationId,
                         @Param("userId") String userId,
                         @Param("now") Instant now);

    @Modifying(clearAutomatically = true)
    @Query(nativeQuery = true, value = """
            UPDATE reservations
               SET status = 'EXPIRED'
             WHERE status      = 'HELD'
               AND expires_at <= :now
            """)
    int markExpired(@Param("now") Instant now);
}
