package com.social.seat_reservation.repository;

import com.social.seat_reservation.domain.model.IdempotencyKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, IdempotencyKey.Key> {

    Optional<IdempotencyKey> findByUserIdAndIdempotencyKey(String userId, String idempotencyKey);

    @Modifying
    @Query(nativeQuery = true, value = """
            INSERT INTO idempotency_keys (user_id, idempotency_key, request_fingerprint, reservation_id)
            VALUES (:userId, :idempotencyKey, :requestFingerprint, :reservationId)
            ON CONFLICT (user_id, idempotency_key) DO NOTHING
            """)
    int insertIfAbsent(@Param("userId") String userId,
                       @Param("idempotencyKey") String idempotencyKey,
                       @Param("requestFingerprint") String requestFingerprint,
                       @Param("reservationId") Long reservationId);
}
