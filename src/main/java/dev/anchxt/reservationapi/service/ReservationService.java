package dev.anchxt.reservationapi.service;

import dev.anchxt.reservationapi.dto.ReservationView;
import dev.anchxt.reservationapi.exception.ConflictException;
import dev.anchxt.reservationapi.exception.NotFoundException;
import dev.anchxt.reservationapi.exception.OverloadedException;
import dev.anchxt.reservationapi.model.SeatStatus;
import dev.anchxt.reservationapi.repository.ReservationRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
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
 * row, then quota row, then seats; the seat lock comes last so whoever takes it has nothing left
 * that can fail. Any decline throws, which rolls the whole transaction back, quota included.
 */
@Service
public class ReservationService {

  private final ReservationRepository repo;
  private final TransactionTemplate tx;
  private final Semaphore admission;
  private final Duration admissionWait;
  private final Counter confirmed;
  private final Counter replayed;

  public ReservationService(
      ReservationRepository repo,
      TransactionTemplate tx,
      Semaphore reserveAdmission,
      @Value("${app.reserve.admission-wait:2s}") Duration admissionWait,
      MeterRegistry meters) {
    this.repo = repo;
    this.tx = tx;
    this.admission = reserveAdmission;
    this.admissionWait = admissionWait;
    this.confirmed = meters.counter("reservations.confirmed");
    this.replayed = meters.counter("reservations.declined", "reason", "idempotent_replay");
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
    var id = UUID.randomUUID();
    ReservationView view;
    // release only what was acquired, trying to release after a failed tryAcquire will mint permits
    try {
      view = claim(id, showId, userId, seats, requestHash, key);
    } finally {
      admission.release();
    }
    // after commit. A replay returns the stored reservation, never the id minted here
    (view.reservationId().equals(id) ? confirmed : replayed).increment();
    return view;
  }

  private ReservationView claim(
      UUID id, long showId, String userId, List<String> seats, String requestHash, String key) {
    return Objects.requireNonNull(
        tx.execute(
            s -> {
              repo.setTimeouts();
              var amount = repo.insertIfAbsent(id, showId, userId, key, requestHash, seats.size());
              if (amount.isEmpty()) {
                return replay(userId, key, requestHash);
              }
              if (!repo.claimQuota(userId, showId, seats.size())) {
                throw new ConflictException("per_user_limit");
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
   * The key is taken (or the show is missing): same request → the stored reservation, seats rebuilt
   * from the hash (a cancel frees them) and its current status; different request → 409. Nothing is
   * written.
   */
  private ReservationView replay(String userId, String key, String requestHash) {
    var r = repo.findByKey(userId, key).orElseThrow(NotFoundException::new);
    if (!r.requestHash().equals(requestHash)) {
      throw new ConflictException("idempotency_conflict");
    }
    return new ReservationView(
        r.id(),
        r.showId(),
        userId,
        seatsOf(r.requestHash()),
        r.amountPaise(),
        r.status().toLowerCase(Locale.ROOT));
  }

  /**
   * Same lock order as reserve: reservation row, quota row, seats. The guarded UPDATE on the
   * reservation row decides, so a repeat cancel changes nothing. Unknown and someone else's are
   * both 404, so existence never leaks. No admission permit: cancels are not the burst, and pool
   * exhaustion is already a 429.
   */
  public void cancel(UUID id, String userId) {
    tx.executeWithoutResult(
        s -> {
          repo.setTimeouts();
          var r = repo.markCancelled(id, userId);
          if (r.isEmpty()) {
            if (!repo.isOwner(id, userId)) {
              throw new NotFoundException();
            }
            return; // already cancelled
          }
          repo.releaseQuota(userId, r.get().showId(), seatsOf(r.get().requestHash()).size());
          repo.releaseSeats(id);
        });
  }

  /** request_hash is "showId:A1,A2"; seat labels never contain ':' or ','. */
  private static List<String> seatsOf(String requestHash) {
    return List.of(requestHash.substring(requestHash.indexOf(':') + 1).split(","));
  }
}
