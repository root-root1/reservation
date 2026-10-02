package com.social.seat_reservation.observability;

import com.social.seat_reservation.domain.enums.DeclineReason;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Counters are pre-registered for every outcome at construction, so a reason that has
 * never occurred still exports as 0 rather than being absent. A dashboard panel for a
 * missing series reads as "no data", which is indistinguishable from "service dead".
 */
@Component
public class ReservationMetrics {

    private final MeterRegistry registry;
    private final Counter confirmed;
    private final Counter replayed;
    private final Map<DeclineReason, Counter> declines = new ConcurrentHashMap<>();
    private final Timer reserveLatency;

    public ReservationMetrics(MeterRegistry registry) {
        this.registry = registry;

        this.confirmed = Counter.builder("reservations_confirmed_total")
                .description("reservations successfully held or confirmed")
                .register(registry);

        this.replayed = Counter.builder("reservations_replayed_total")
                .description("idempotent retries served from an existing reservation")
                .register(registry);

        this.reserveLatency = Timer.builder("reservation_reserve_duration")
                .description("end to end duration of a reserve attempt")
                .publishPercentileHistogram()
                .register(registry);

        for (DeclineReason reason : DeclineReason.values()) {
            declines.put(reason, declineCounter(reason));
        }
    }

    private Counter declineCounter(DeclineReason reason) {
        return Counter.builder("reservations_declined_total")
                .description("reservation attempts declined, by reason")
                .tag("reason", reason.getWireValue())
                .register(registry);
    }

    public void confirmed() {
        confirmed.increment();
    }

    public void replayed() {
        replayed.increment();
    }

    public void declined(DeclineReason reason) {
        declines.computeIfAbsent(reason, this::declineCounter).increment();
    }

    public Timer reserveLatency() {
        return reserveLatency;
    }
}
