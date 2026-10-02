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

import java.time.Instant;

@Entity
@Table(name = "seats")
public class Seat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "show_id", nullable = false, updatable = false)
    private Long showId;

    @Column(nullable = false, updatable = false)
    private String label;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private SeatStatus status;

    @Column(name = "claimed_by")
    private String claimedBy;

    @Column(name = "hold_expires_at")
    private Instant holdExpiresAt;

    @Column(name = "reservation_id")
    private Long reservationId;

    protected Seat() {
    }

    public Seat(Long showId, String label) {
        this.showId = showId;
        this.label = label;
        this.status = SeatStatus.AVAILABLE;
    }

    public Long getId() {
        return id;
    }

    public Long getShowId() {
        return showId;
    }

    public String getLabel() {
        return label;
    }

    public SeatStatus getStatus() {
        return status;
    }

    public String getClaimedBy() {
        return claimedBy;
    }

    public Instant getHoldExpiresAt() {
        return holdExpiresAt;
    }

    public Long getReservationId() {
        return reservationId;
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
