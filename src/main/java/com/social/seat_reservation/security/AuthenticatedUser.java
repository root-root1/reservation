package com.social.seat_reservation.security;

public record AuthenticatedUser(String userId, Role role) {

    public boolean isAdmin() {
        return role == Role.ADMIN;
    }
}
