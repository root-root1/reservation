package com.social.seat_reservation.observability;

import com.social.seat_reservation.api.error.GlobalExceptionHandler;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Gives every request a correlation id, taken from the caller when supplied so a
 * trace survives across hops, generated otherwise. Highest precedence, so the id is
 * in the MDC before anything else can log.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    private static final int MAX_LENGTH = 64;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        String requestId = sanitise(request.getHeader(HEADER));

        MDC.put(GlobalExceptionHandler.REQUEST_ID_KEY, requestId);
        response.setHeader(HEADER, requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(GlobalExceptionHandler.REQUEST_ID_KEY);
        }
    }

    /**
     * An inbound header is attacker controlled: cap the length and allow only
     * characters that cannot forge extra fields in a log line.
     */
    private String sanitise(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return UUID.randomUUID().toString();
        }
        String trimmed = candidate.trim();
        if (trimmed.length() > MAX_LENGTH || !trimmed.matches("[A-Za-z0-9._:-]+")) {
            return UUID.randomUUID().toString();
        }
        return trimmed;
    }
}
