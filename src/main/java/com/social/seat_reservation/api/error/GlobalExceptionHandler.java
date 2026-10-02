package com.social.seat_reservation.api.error;

import com.social.seat_reservation.domain.enums.DeclineReason;
import com.social.seat_reservation.domain.exception.DomainException;
import com.social.seat_reservation.observability.ReservationMetrics;
import jakarta.validation.ConstraintViolationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.concurrent.TimeoutException;

@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    public static final String REQUEST_ID_KEY = "requestId";

    private final ReservationMetrics metrics;

    @ExceptionHandler(DomainException.class)
    public ResponseEntity<ErrorResponse> handleDomain(DomainException e) {
        DeclineReason reason = e.getReason();
        log.info("decline reason={} message={}", reason.getWireValue(), e.getMessage());
        return respond(reason, e.getMessage());
    }

    @ExceptionHandler({
            MethodArgumentNotValidException.class,
            ConstraintViolationException.class,
            HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class,
            MissingRequestHeaderException.class
    })
    public ResponseEntity<ErrorResponse> handleBadRequest(Exception e) {
        log.info("decline reason=validation_failed type={}", e.getClass().getSimpleName());
        return respond(DeclineReason.VALIDATION_FAILED, "request is malformed or fails validation");
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ErrorResponse> handleUnauthenticated(AuthenticationException e) {
        return respond(DeclineReason.UNAUTHENTICATED, "authentication required");
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleForbidden(AccessDeniedException e) {
        return respond(DeclineReason.NOT_OWNER, "not permitted");
    }

    /**
     * Row-lock contention on a hot seat. Postgres aborts the waiter once lock_timeout
     * passes; the seat is taken as far as this caller is concerned, so it is a 409
     * domain decline. Letting this escape is the single most likely source of 5xx
     * during a stampede.
     */
    @ExceptionHandler({
            CannotAcquireLockException.class,
            PessimisticLockingFailureException.class,
            QueryTimeoutException.class,
            TimeoutException.class
    })
    public ResponseEntity<ErrorResponse> handleLockContention(Exception e) {
        log.info("decline reason=seat_taken cause=lock_contention type={}", e.getClass().getSimpleName());
        return respond(DeclineReason.SEAT_TAKEN, "seat is being claimed by another request");
    }

    /**
     * A unique constraint fired, which means another transaction won the same row
     * between our read and our write. That is a race we lost, not a server fault.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraint(DataIntegrityViolationException e) {
        log.info("decline reason=seat_taken cause=constraint_violation");
        return respond(DeclineReason.SEAT_TAKEN, "the requested resource was claimed concurrently");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
        log.error("unhandled exception type={}", e.getClass().getName(), e);
        return ResponseEntity.status(500)
                .body(new ErrorResponse("internal_error", "unexpected server error", requestId()));
    }

    private ResponseEntity<ErrorResponse> respond(DeclineReason reason, String message) {
        metrics.declined(reason);
        return ResponseEntity.status(reason.getHttpStatus())
                .body(ErrorResponse.of(reason, message, requestId()));
    }

    private String requestId() {
        return MDC.get(REQUEST_ID_KEY);
    }
}
