package com.social.seat_reservation.api.controller;

import com.social.seat_reservation.api.dto.request.CreateShowRequest;
import com.social.seat_reservation.api.dto.response.ShowResponse;
import com.social.seat_reservation.config.ReservationProperties;
import com.social.seat_reservation.domain.model.Show;
import com.social.seat_reservation.service.ShowService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/shows")
@RequiredArgsConstructor
public class ShowController {

    private final ShowService showService;
    private final ReservationProperties properties;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ShowResponse create(@Valid @RequestBody CreateShowRequest request) {
        short perUserLimit = request.perUserLimit() == null
                ? (short) properties.perUserLimit()
                : request.perUserLimit().shortValue();

        Show show = showService.createShow(request.name(), request.seats(), request.pricePaise(), perUserLimit);
        return ShowResponse.created(show, request.seats());
    }

    @GetMapping("/{id}")
    public ShowResponse get(@PathVariable UUID id) {
        return ShowResponse.of(showService.getShowState(id));
    }
}
