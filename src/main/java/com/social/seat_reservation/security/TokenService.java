package com.social.seat_reservation.security;

import com.social.seat_reservation.config.AuthProperties;
import com.social.seat_reservation.domain.exception.UnauthenticatedException;
import com.social.seat_reservation.domain.model.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Date;

@Service
public class TokenService {

    private static final String ROLE_CLAIM = "role";
    private static final int MIN_SECRET_BYTES = 32;

    private final SecretKey key;
    private final String issuer;
    private final Duration ttl;
    private final Clock clock;

    public TokenService(AuthProperties properties, Clock clock) {
        byte[] secret = properties.jwtSecret().getBytes(StandardCharsets.UTF_8);
        if (secret.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "app.auth.jwt-secret must be at least " + MIN_SECRET_BYTES + " bytes for HS256");
        }
        this.key = Keys.hmacShaKeyFor(secret);
        this.issuer = properties.jwtIssuer();
        this.ttl = Duration.ofMinutes(properties.tokenTtlMinutes());
        this.clock = clock;
    }

    public String issue(User user) {
        Date issuedAt = Date.from(clock.instant());
        Date expiry = Date.from(clock.instant().plus(ttl));

        return Jwts.builder()
                .issuer(issuer)
                .subject(user.getPublicId().toString())
                .claim(ROLE_CLAIM, user.getRole().name())
                .issuedAt(issuedAt)
                .expiration(expiry)
                .signWith(key)
                .compact();
    }

    public AuthenticatedUser parse(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .requireIssuer(issuer)
                    .clock(() -> Date.from(clock.instant()))
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            return new AuthenticatedUser(claims.getSubject(), Role.valueOf(claims.get(ROLE_CLAIM, String.class)));
        } catch (JwtException | IllegalArgumentException e) {
            throw new UnauthenticatedException("invalid or expired token");
        }
    }
}
