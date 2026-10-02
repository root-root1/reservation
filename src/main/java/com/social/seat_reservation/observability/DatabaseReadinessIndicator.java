package com.social.seat_reservation.observability;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;

/**
 * Readiness check for the one dependency this service cannot work without.
 *
 * <p>Fails closed: anything other than a connection that answers within the timeout is
 * reported as not ready. A service that cannot reach Postgres cannot decide who owns a
 * seat, so it must stop receiving traffic rather than guess.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DatabaseReadinessIndicator {

    private static final int VALIDATION_TIMEOUT_SECONDS = 2;

    private final DataSource dataSource;

    public boolean isReady() {
        try (Connection connection = dataSource.getConnection()) {
            return connection.isValid(VALIDATION_TIMEOUT_SECONDS);
        } catch (Exception e) {
            log.warn("readiness check failed: {}", e.getMessage());
            return false;
        }
    }
}
