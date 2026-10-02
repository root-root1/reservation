package com.social.seat_reservation.config;

import com.social.seat_reservation.repository.UserRepository;
import com.social.seat_reservation.security.Role;
import com.social.seat_reservation.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class AdminBootstrap {

    private final AuthProperties properties;
    private final UserRepository userRepository;
    private final UserService userService;

    @Bean
    public ApplicationRunner seedAdmin() {
        return args -> {
            String username = properties.adminUsername();
            String password = properties.adminPassword();

            if (username == null || username.isBlank() || password == null || password.isBlank()) {
                log.info("admin bootstrap skipped: app.auth.admin-username/password not configured");
                return;
            }
            if (userRepository.existsByUsername(username)) {
                return;
            }
            userService.register(username, password, Role.ADMIN);
            log.info("admin bootstrap created user '{}'", username);
        };
    }
}
