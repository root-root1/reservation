package com.social.seat_reservation.api.dto.response;

import com.social.seat_reservation.domain.model.Show;
import com.social.seat_reservation.service.ShowState;

import java.util.List;
import java.util.UUID;

public record ShowResponse(
        UUID id,
        String name,
        long pricePaise,
        int perUserLimit,
        int totalSeats,
        long availableCount,
        long heldCount,
        long confirmedCount,
        List<SeatResponse> seats
) {

    public static ShowResponse created(Show show, List<String> labels) {
        return new ShowResponse(
                show.getPublicId(),
                show.getName(),
                show.getPricePaise(),
                show.getPerUserLimit(),
                show.getTotalSeats(),
                labels.size(),
                0L,
                0L,
                labels.stream().map(label -> new SeatResponse(label, "available")).toList());
    }

    public static ShowResponse of(ShowState state) {
        Show show = state.show();
        return new ShowResponse(
                show.getPublicId(),
                show.getName(),
                show.getPricePaise(),
                show.getPerUserLimit(),
                show.getTotalSeats(),
                state.counts().getAvailableCount(),
                state.counts().getHeldCount(),
                state.counts().getConfirmedCount(),
                state.seats().stream()
                        .map(seat -> SeatResponse.of(seat.label(), seat.status()))
                        .toList());
    }
}
