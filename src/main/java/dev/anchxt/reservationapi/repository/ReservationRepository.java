package dev.anchxt.reservationapi.repository;

import dev.anchxt.reservationapi.exception.ConflictException;
import dev.anchxt.reservationapi.model.SeatStatus;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Reserve-path SQL. Callers run these in one transaction, in this file's order. */
@Repository
public class ReservationRepository {

  private final JdbcClient jdbc;

  public ReservationRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Transaction-local: lock waits and slow statements fail fast instead of blocking a connection.
   */
  public void setTimeouts() {
    jdbc.sql(
            "SELECT set_config('lock_timeout', '500ms', true),"
                + " set_config('statement_timeout', '2s', true)")
        .query()
        .singleRow();
  }

  /** Inserts the reservation priced from the show row; returns the amount, empty if no show. */
  public Optional<Long> insert(
      UUID id, long showId, String userId, String key, String requestHash, int seats) {
    return jdbc.sql(
            "INSERT INTO reservations"
                + " (id, show_id, user_id, idempotency_key, request_hash, amount_paise, status)"
                + " SELECT ?, id, ?, ?, ?, price_paise * ?, 'CONFIRMED' FROM shows WHERE id = ?"
                + " RETURNING amount_paise")
        .params(id, userId, key, requestHash, seats, showId)
        .query(Long.class)
        .optional();
  }

  /**
   * Row-locks the requested seats in seat_no order and returns their statuses. NOWAIT: a seat
   * locked by another transaction is a decline, never a wait, so no wait-for cycle (deadlock) can
   * form across seats. Unknown seats are simply absent from the result.
   */
  public List<SeatStatus> lockSeats(long showId, List<String> seatNos) {
    try {
      return jdbc.sql(
              "SELECT status FROM seats WHERE show_id = ? AND seat_no = ANY(?::text[])"
                  + " ORDER BY seat_no FOR UPDATE NOWAIT")
          .params(showId, seatNos.toArray(String[]::new))
          .query((rs, n) -> SeatStatus.valueOf(rs.getString(1)))
          .list();
    } catch (DataAccessException e) {
      if (e.getMostSpecificCause() instanceof SQLException s && "55P03".equals(s.getSQLState())) {
        throw new ConflictException("seat_taken");
      }
      throw e;
    }
  }

  /** Only after lockSeats saw every seat AVAILABLE: the row locks make this write uncontested. */
  public void confirmSeats(UUID reservationId, long showId, List<String> seatNos) {
    jdbc.sql(
            "UPDATE seats SET status = 'CONFIRMED', reservation_id = ?"
                + " WHERE show_id = ? AND seat_no = ANY(?::text[])")
        .params(reservationId, showId, seatNos.toArray(String[]::new))
        .update();
  }
}
