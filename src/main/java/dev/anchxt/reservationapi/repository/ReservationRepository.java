package dev.anchxt.reservationapi.repository;

import dev.anchxt.reservationapi.exception.ConflictException;
import dev.anchxt.reservationapi.model.SeatStatus;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Reserve and cancel SQL. Callers run each flow in one transaction, in this file's order: both take
 * the reservation row, then the quota row, then seats.
 */
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

  /** A stored reservation, as needed to replay or cancel it. */
  public record Existing(
      UUID id, long showId, String requestHash, long amountPaise, String status) {}

  private static final String EXISTING_COLUMNS = "id, show_id, request_hash, amount_paise, status";

  private static final RowMapper<Existing> EXISTING =
      (rs, n) ->
          new Existing(
              rs.getObject(1, UUID.class),
              rs.getLong(2),
              rs.getString(3),
              rs.getLong(4),
              rs.getString(5));

  /**
   * Inserts the reservation priced from the show row; returns the amount. Empty if the show does
   * not exist or (user, key) is already taken. A same-key insert still in flight elsewhere makes
   * this wait on the unique index until that transaction ends: commit means empty here, rollback
   * means this insert goes through.
   */
  public Optional<Long> insertIfAbsent(
      UUID id, long showId, String userId, String key, String requestHash, int seats) {
    return jdbc.sql(
            "INSERT INTO reservations"
                + " (id, show_id, user_id, idempotency_key, request_hash, amount_paise, status)"
                + " SELECT ?, id, ?, ?, ?, price_paise * ?, 'CONFIRMED' FROM shows WHERE id = ?"
                + " ON CONFLICT (user_id, idempotency_key) DO NOTHING"
                + " RETURNING amount_paise")
        .params(id, userId, key, requestHash, seats, showId)
        .query(Long.class)
        .optional();
  }

  /** Read committed: a new statement, so it sees the row that made insertIfAbsent a no-op. */
  public Optional<Existing> findByKey(String userId, String key) {
    return jdbc.sql(
            "SELECT "
                + EXISTING_COLUMNS
                + " FROM reservations"
                + " WHERE user_id = ? AND idempotency_key = ?")
        .params(userId, key)
        .query(EXISTING)
        .optional();
  }

  /**
   * Adds {@code seats} to the user's held count for the show; false if that would pass the limit.
   * One atomic upsert, never count-then-check: same-user requests serialize on the quota row, and
   * the WHERE is re-checked against the latest committed count. The insert path is guarded too
   * ({@code seats <= per_user_limit}), and ck_quota_bounds backs both up. max_held is copied from
   * the show on first insert. The caller has already checked that the show exists.
   */
  public boolean claimQuota(String userId, long showId, int seats) {
    return jdbc.sql(
            "INSERT INTO user_show_quota (user_id, show_id, held, max_held)"
                + " SELECT :user, s.id, :seats, s.per_user_limit FROM shows s"
                + " WHERE s.id = :show AND :seats <= s.per_user_limit"
                + " ON CONFLICT (user_id, show_id) DO UPDATE"
                + " SET held = user_show_quota.held + :seats"
                + " WHERE user_show_quota.held + :seats <= user_show_quota.max_held"
                + " RETURNING held")
        .param("user", userId)
        .param("show", showId)
        .param("seats", seats)
        .query(Integer.class)
        .optional()
        .isPresent();
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

  public Optional<Existing> markCancelled(UUID id, String userId) {
    return jdbc.sql(
            "UPDATE reservations SET status = 'CANCELLED'"
                + " WHERE id = ? AND user_id = ? AND status = 'CONFIRMED'"
                + " RETURNING "
                + EXISTING_COLUMNS)
        .params(id, userId)
        .query(EXISTING)
        .optional();
  }

  /** Only picks the response for a cancel that changed nothing; never decides a write. */
  public boolean isOwner(UUID id, String userId) {
    return jdbc.sql("SELECT EXISTS (SELECT 1 FROM reservations WHERE id = ? AND user_id = ?)")
        .params(id, userId)
        .query(Boolean.class)
        .single();
  }

  /** Cancel step 2. ck_quota_bounds rejects going below zero. */
  public void releaseQuota(String userId, long showId, int seats) {
    jdbc.sql("UPDATE user_show_quota SET held = held - ? WHERE user_id = ? AND show_id = ?")
        .params(seats, userId, showId)
        .update();
  }

  /** Cancel step 3: by owner id, so a seat since confirmed to someone else is never touched. */
  public void releaseSeats(UUID reservationId) {
    jdbc.sql(
            "UPDATE seats SET status = 'AVAILABLE', reservation_id = NULL"
                + " WHERE reservation_id = ?")
        .params(reservationId)
        .update();
  }
}
