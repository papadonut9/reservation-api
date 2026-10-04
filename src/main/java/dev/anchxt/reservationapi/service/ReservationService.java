package dev.anchxt.reservationapi.service;

import dev.anchxt.reservationapi.dto.ReservationView;
import dev.anchxt.reservationapi.exception.ConflictException;
import dev.anchxt.reservationapi.exception.NotFoundException;
import dev.anchxt.reservationapi.exception.OverloadedException;
import dev.anchxt.reservationapi.model.SeatStatus;
import dev.anchxt.reservationapi.repository.ReservationRepository;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
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
  private final Semaphore admission;
  private final Duration admissionWait;

  public ReservationService(
      ReservationRepository repo,
      TransactionTemplate tx,
      Semaphore reserveAdmission,
      @Value("${app.reserve.admission-wait:2s}") Duration admissionWait) {
    this.repo = repo;
    this.tx = tx;
    this.admission = reserveAdmission;
    this.admissionWait = admissionWait;
  }

  public ReservationView reserve(long showId, String userId, List<String> requested, String key) {
    // sorted + deduped; String order equals seat_no's COLLATE "C" order for [A-Za-z0-9-]
    var seats = List.copyOf(new TreeSet<>(requested));
    var requestHash = showId + ":" + String.join(",", seats);
    try {
      if (!admission.tryAcquire(admissionWait.toMillis(), TimeUnit.MILLISECONDS)) {
        throw new OverloadedException();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new OverloadedException();
    }
    // release only what was acquired, trying to release after a failed tryAcquire will mint permits
    try {
      return claim(showId, userId, seats, requestHash, key);
    } finally {
      admission.release();
    }
  }

  private ReservationView claim(
      long showId, String userId, List<String> seats, String requestHash, String key) {
    var id = UUID.randomUUID();
    return Objects.requireNonNull(
        tx.execute(
            s -> {
              repo.setTimeouts();
              var amount = repo.insertIfAbsent(id, showId, userId, key, requestHash, seats.size());
              if (amount.isEmpty()) {
                return replay(userId, key, requestHash);
              }
              var locked = repo.lockSeats(showId, seats);
              if (locked.size() != seats.size()) {
                throw new NotFoundException();
              }
              if (locked.stream().anyMatch(st -> st != SeatStatus.AVAILABLE)) {
                throw new ConflictException("seat_taken");
              }
              repo.confirmSeats(id, showId, seats);
              return new ReservationView(id, showId, userId, seats, amount.get(), "confirmed");
            }));
  }

  /**
   * The key is taken (or the show is missing): same request → the original 201 body, rebuilt from
   * the stored hash so it survives a later cancel; different request → 409. Nothing is written.
   */
  private ReservationView replay(String userId, String key, String requestHash) {
    var r = repo.findByKey(userId, key).orElseThrow(NotFoundException::new);
    if (!r.requestHash().equals(requestHash)) {
      throw new ConflictException("idempotency_conflict");
    }
    // hash is "showId:A1,A2"; seat labels never contain ':' or ','
    var seats = List.of(r.requestHash().substring(r.requestHash().indexOf(':') + 1).split(","));
    return new ReservationView(r.id(), r.showId(), userId, seats, r.amountPaise(), "confirmed");
  }
}
