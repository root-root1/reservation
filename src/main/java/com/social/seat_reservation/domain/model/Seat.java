package com.social.seat_reservation.domain.model;

import com.social.seat_reservation.domain.enums.SeatStatus;
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

@Entity
@Table(name = "seats")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Seat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "show_id", nullable = false, updatable = false)
    private Long showId;

    @Column(nullable = false, updatable = false, length = 16)
    private String label;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private SeatStatus status;

    @Column(name = "claimed_by", length = 64)
    private String claimedBy;

    @Column(name = "hold_expires_at")
    private Instant holdExpiresAt;

    @Column(name = "reservation_id")
    private Long reservationId;

    public Seat(Long showId, String label) {
        this.showId = showId;
        this.label = label;
        this.status = SeatStatus.AVAILABLE;
    }








    public SeatStatus effectiveStatus(Instant now) {
        if (status == SeatStatus.HELD && holdExpiresAt != null && !holdExpiresAt.isAfter(now)) {
            return SeatStatus.AVAILABLE;
        }
        return status;
    }

    public boolean isClaimableAt(Instant now) {
        return effectiveStatus(now) == SeatStatus.AVAILABLE;
    }
}
