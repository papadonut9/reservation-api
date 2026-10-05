package dev.anchxt.reservationapi.repository;

import dev.anchxt.reservationapi.model.Seat;
import dev.anchxt.reservationapi.model.SeatStatus;
import dev.anchxt.reservationapi.model.Show;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ShowRepository {

  private final JdbcClient jdbc;

  public ShowRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  // Plain MVCC reads: no row locks taken, never waits on reserve/cancel locks, never blocks them.
  // A seat locked by an uncommitted claim reads as available until that claim commits.
  // The timeout is SET LOCAL, so it covers every read in the caller's transaction.
  public Optional<Show> findShow(long id) {
    jdbc.sql("SET LOCAL statement_timeout = '1s'").update();
    return jdbc.sql(
            "SELECT id, name, price_paise, per_user_limit, total_seats FROM shows WHERE id = ?")
        .param(id)
        .query(
            (rs, n) ->
                new Show(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getInt(4), rs.getInt(5)))
        .optional();
  }

  public List<Seat> findSeats(long showId) {
    return jdbc.sql(
            "SELECT seat_no, status, reservation_id FROM seats WHERE show_id = ? ORDER BY seat_no")
        .param(showId)
        .query(
            (rs, n) ->
                new Seat(
                    new Seat.Id(showId, rs.getString(1)),
                    SeatStatus.valueOf(rs.getString(2)),
                    rs.getObject(3, UUID.class)))
        .list();
  }

  public record SeatCount(long showId, String status, long count) {}

  /**
   * Seat count per show and status, zeros included (a sold-out show still reports available=0).
   * Plain read, no locks; one scan of seats.
   */
  public List<SeatCount> countSeats() {
    return jdbc.sql(
            "SELECT s.id, st.status, count(seat.seat_no) FROM shows s"
                + " CROSS JOIN (VALUES ('AVAILABLE'), ('HELD'), ('CONFIRMED')) st(status)"
                + " LEFT JOIN seats seat ON seat.show_id = s.id AND seat.status = st.status"
                + " GROUP BY s.id, st.status")
        .query((rs, n) -> new SeatCount(rs.getLong(1), rs.getString(2), rs.getLong(3)))
        .list();
  }

  /** Inserts the show and every seat AVAILABLE; returns the generated id. */
  public long insert(Show show, List<String> seatNos) {
    long id =
        jdbc.sql("INSERT INTO shows (name, price_paise, total_seats) VALUES (?, ?, ?) RETURNING id")
            .params(show.getName(), show.getPricePaise(), show.getTotalSeats())
            .query(Long.class)
            .single();
    // one statement, one round trip, whatever the seat count
    jdbc.sql("INSERT INTO seats (show_id, seat_no) SELECT ?, unnest(?::text[])")
        .params(id, seatNos.toArray(String[]::new))
        .update();
    return id;
  }
}
