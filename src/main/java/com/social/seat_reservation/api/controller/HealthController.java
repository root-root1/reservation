package com.social.seat_reservation.api.controller;

import com.social.seat_reservation.observability.DatabaseReadinessIndicator;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequiredArgsConstructor
public class HealthController {

    private final DatabaseReadinessIndicator databaseReadiness;

    /**
     * Liveness: is the JVM answering? Deliberately touches no dependency — a slow
     * database must mark the instance unready, not get the container killed and
     * restarted into the same slow database.
     */
    @GetMapping("/healthz")
    public Map<String, String> liveness() {
        return Map.of("status", "alive");
    }

    /**
     * Readiness: can this instance actually serve a reservation? Fails closed with 503
     * so the platform stops routing traffic here while Postgres is unreachable.
     */
    @GetMapping("/readyz")
    public ResponseEntity<Map<String, String>> readiness() {
        boolean ready = databaseReadiness.isReady();

        return ResponseEntity
                .status(ready ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of(
                        "status", ready ? "ready" : "not_ready",
                        "database", ready ? "up" : "down"));
    }
}
