package com.social.seat_reservation.service;

import com.social.seat_reservation.domain.enums.SeatStatus;
import com.social.seat_reservation.domain.model.Show;
import com.social.seat_reservation.repository.SeatCounts;

import java.util.List;

public record ShowState(Show show, List<SeatView> seats, SeatCounts counts) {

    public record SeatView(String label, SeatStatus status) {
    }
}
