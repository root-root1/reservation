package com.social.seat_reservation.service;

import com.social.seat_reservation.common.UuidV7;
import com.social.seat_reservation.domain.exception.UnauthenticatedException;
import com.social.seat_reservation.domain.exception.UserAlreadyExistsException;
import com.social.seat_reservation.domain.model.User;
import com.social.seat_reservation.repository.UserRepository;
import com.social.seat_reservation.security.Role;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Transactional
    public User register(String username, String rawPassword, Role role) {
        try {
            return userRepository.saveAndFlush(
                    new User(UuidV7.generate(), username, passwordEncoder.encode(rawPassword), role));
        } catch (DataIntegrityViolationException e) {
            throw new UserAlreadyExistsException(username);
        }
    }

    @Transactional(readOnly = true)
    public User authenticate(String username, String rawPassword) {
        return userRepository.findByUsername(username)
                .filter(user -> passwordEncoder.matches(rawPassword, user.getPasswordHash()))
                .orElseThrow(() -> new UnauthenticatedException("invalid username or password"));
    }
}
