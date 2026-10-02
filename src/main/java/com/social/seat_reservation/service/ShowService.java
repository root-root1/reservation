package com.social.seat_reservation.service;

import com.social.seat_reservation.domain.exception.ShowAlreadyExistsException;
import com.social.seat_reservation.domain.exception.ShowNotFoundException;
import com.social.seat_reservation.domain.exception.ValidationFailedException;
import com.social.seat_reservation.domain.model.Show;
import com.social.seat_reservation.repository.SeatRepository;
import com.social.seat_reservation.repository.ShowRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ShowService {

    private final ShowRepository showRepository;
    private final SeatRepository seatRepository;
    private final Clock clock;

    @Transactional
    public Show createShow(String name, List<String> labels, long pricePaise, short perUserLimit) {
        if (showRepository.existsByName(name)) {
            throw new ShowAlreadyExistsException(name);
        }
        Show show = showRepository.save(new Show(UUID.randomUUID(), name,pricePaise,perUserLimit, labels.size()));
        seatRepository.bulkInsertSeats(show.getId(), toPostgresArrayLiteral(labels));
        return show;
    }

    @Transactional(readOnly = true)
    public ShowState getShowState(UUID publicId) {
        Show show = showRepository.findByPublicId(publicId).orElseThrow(ShowNotFoundException::new);
        Instant now = clock.instant();
        List<ShowState.SeatView> seats = seatRepository.findByShowIdOrderByIdAsc(show.getId()).stream()
                .map(s -> new ShowState.SeatView(s.getLabel(), s.effectiveStatus(now)))
                .toList();
        return new ShowState(show, seats, seatRepository.countByEffectiveStatus(show.getId(), now));
    }

    @Transactional(readOnly = true)
    public Show requireById(Long id) {
        return showRepository.findById(id).orElseThrow(ShowNotFoundException::new);
    }

    @Transactional(readOnly = true)
    public Show requireByPublicId(UUID publicId) {
        return showRepository.findByPublicId(publicId).orElseThrow(ShowNotFoundException::new);
    }

    private String toPostgresArrayLiteral(List<String> labels) {
        if (labels == null || labels.isEmpty()) {
            throw new ValidationFailedException("a show must have at least one seat");
        }
        if (new LinkedHashSet<>(labels).size() != labels.size()) {
            throw new ValidationFailedException("seat labels must be unique within a show");
        }

        StringBuilder literal = new StringBuilder(labels.size() * 6).append('{');
        for (int i = 0; i < labels.size(); i++) {
            String label = labels.get(i);
            if (label == null || label.isBlank()) {
                throw new ValidationFailedException("seat labels must not be blank");
            }
            if (i > 0) {
                literal.append(',');
            }
            literal.append('"');
            for (int c = 0; c < label.length(); c++) {
                char ch = label.charAt(c);
                if (ch == '"' || ch == '\\') {
                    literal.append('\\');
                }
                literal.append(ch);
            }
            literal.append('"');
        }
        return literal.append('}').toString();
    }
}
