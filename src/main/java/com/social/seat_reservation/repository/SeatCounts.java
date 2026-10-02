package com.social.seat_reservation.repository;

public interface SeatCounts {

    long getAvailableCount();

    long getHeldCount();

    long getConfirmedCount();

    long getTotalCount();
}
