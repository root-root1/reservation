package com.social.seat_reservation.domain.model;

import com.social.seat_reservation.domain.enums.ReservationStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "reservations")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Reservation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id", nullable = false, updatable = false)
    private UUID publicId;

    @Column(name = "show_id", nullable = false, updatable = false)
    private Long showId;

    @Column(name = "user_id", nullable = false, updatable = false, length = 64)
    private String userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ReservationStatus status;

    @Column(name = "amount_paise", nullable = false)
    private long amountPaise;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    public Reservation(UUID publicId, Long showId, String userId, long amountPaise, Instant expiresAt) {
        this.publicId = publicId;
        this.showId = showId;
        this.userId = userId;
        this.status = ReservationStatus.HELD;
        this.amountPaise = amountPaise;
        this.expiresAt = expiresAt;
    }









    public boolean isOwnedBy(String candidateUserId) {
        return userId.equals(candidateUserId);
    }

    public ReservationStatus effectiveStatus(Instant now) {
        if (status == ReservationStatus.HELD && expiresAt != null && !expiresAt.isAfter(now)) {
            return ReservationStatus.EXPIRED;
        }
        return status;
    }
}
