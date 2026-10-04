package dev.anchxt.reservationapi.service;

import dev.anchxt.reservationapi.dto.ReservationView;
import dev.anchxt.reservationapi.exception.ConflictException;
import dev.anchxt.reservationapi.exception.NotFoundException;
import dev.anchxt.reservationapi.model.SeatStatus;
import dev.anchxt.reservationapi.repository.ReservationRepository;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reserve: all-or-nothing over the requested seats, in one transaction. Lock order is reservation
 * row, then seats; the seat lock comes last so whoever takes it has nothing left that can fail. Any
 * decline throws, which rolls the whole transaction back.
 */
@Service
public class ReservationService {

  private final ReservationRepository repo;
  private final TransactionTemplate tx;

  public ReservationService(ReservationRepository repo, TransactionTemplate tx) {
    this.repo = repo;
    this.tx = tx;
  }

  public ReservationView reserve(long showId, String userId, List<String> requested, String key) {
    // sorted + deduped; String order equals seat_no's COLLATE "C" order for [A-Za-z0-9-]
    var seats = List.copyOf(new TreeSet<>(requested));
    var requestHash = showId + ":" + String.join(",", seats);
    var id = UUID.randomUUID();
    long amount =
        Objects.requireNonNull(
            tx.execute(
                s -> {
                  repo.setTimeouts();
                  long a =
                      repo.insert(id, showId, userId, key, requestHash, seats.size())
                          .orElseThrow(NotFoundException::new);
                  var locked = repo.lockSeats(showId, seats);
                  if (locked.size() != seats.size()) {
                    throw new NotFoundException();
                  }
                  if (locked.stream().anyMatch(st -> st != SeatStatus.AVAILABLE)) {
                    throw new ConflictException("seat_taken");
                  }
                  repo.confirmSeats(id, showId, seats);
                  return a;
                }));
    return new ReservationView(id, showId, userId, seats, amount, "confirmed");
  }
}
