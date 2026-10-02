package com.social.seat_reservation.repository;

import com.social.seat_reservation.domain.model.Show;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ShowRepository extends JpaRepository<Show, Long> {

    Optional<Show> findByPublicId(UUID publicId);

    boolean existsByName(String name);
}
