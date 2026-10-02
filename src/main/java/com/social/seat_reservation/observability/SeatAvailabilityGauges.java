package com.social.seat_reservation.observability;

import com.social.seat_reservation.domain.model.Show;
import com.social.seat_reservation.repository.SeatCounts;
import com.social.seat_reservation.repository.SeatRepository;
import com.social.seat_reservation.repository.ShowRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-show seat gauges, refreshed on a short interval rather than computed when
 * Prometheus scrapes. A scrape must never run a query against the hot table — that
 * would let a monitoring system add load to the system it is monitoring.
 *
 * <p>Counts come from the same effective-status query the API uses, so metrics and
 * {@code GET /shows/{id}} cannot disagree.
 */
@Component
@RequiredArgsConstructor
public class SeatAvailabilityGauges {

    private final MeterRegistry registry;
    private final ShowRepository showRepository;
    private final SeatRepository seatRepository;
    private final Clock clock;

    private final Map<String, AtomicLong> values = new ConcurrentHashMap<>();

    @Scheduled(fixedDelayString = "${app.metrics.gauge-refresh-ms:5000}")
    @Transactional(readOnly = true)
    public void refresh() {
        Instant now = clock.instant();

        for (Show show : showRepository.findAll()) {
            SeatCounts counts = seatRepository.countByEffectiveStatus(show.getId(), now);
            String showId = show.getPublicId().toString();

            holder("seats_available", showId).set(counts.getAvailableCount());
            holder("seats_held", showId).set(counts.getHeldCount());
            holder("seats_confirmed", showId).set(counts.getConfirmedCount());
            holder("seats_total", showId).set(counts.getTotalCount());
        }
    }

    private AtomicLong holder(String name, String showId) {
        return values.computeIfAbsent(name + "|" + showId, key -> {
            AtomicLong value = new AtomicLong();
            Gauge.builder(name, value, AtomicLong::doubleValue)
                    .description("seat counts by effective status")
                    .tag("show", showId)
                    .register(registry);
            return value;
        });
    }
}
