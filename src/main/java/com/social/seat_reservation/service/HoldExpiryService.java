package com.social.seat_reservation.service;

import com.social.seat_reservation.repository.ReservationRepository;
import com.social.seat_reservation.repository.SeatRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

@Service
@RequiredArgsConstructor
public class HoldExpiryService {

    private final SeatRepository seatRepository;
    private final ReservationRepository reservationRepository;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${app.reservation.sweep-interval-ms:30000}")
    @Transactional
    public int sweepExpiredHolds() {
        throw new UnsupportedOperationException();
    }
}
