package dev.anchxt.reservationapi.show;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * POST is admin-only (see SecurityConfig): show row + every seat AVAILABLE, in one transaction. GET
 * is public (no token): seats grouped by status, counts from the same snapshot.
 */
@RestController
class ShowController {

  private static final Logger log = LoggerFactory.getLogger(ShowController.class);
  private static final List<String> STATUSES = List.of("available", "held", "confirmed");

  private final JdbcClient jdbc;
  private final TransactionTemplate tx;
  private final TransactionTemplate snapshot;

  ShowController(JdbcClient jdbc, TransactionTemplate tx) {
    this.jdbc = jdbc;
    this.tx = tx;
    // shared bean is mutable; own copy so GET's isolation never leaks into writes
      assert tx.getTransactionManager() != null;
      this.snapshot = new TransactionTemplate(tx.getTransactionManager());
    snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    snapshot.setReadOnly(true);
  }

  record ShowView(
      long id,
      String name,
      @JsonProperty("total_seats") int totalSeats,
      Map<String, Integer> counts,
      Map<String, List<String>> seats) {}

  @GetMapping("/shows/{id}")
  ShowView get(@PathVariable long id) {
    return snapshot.execute(s -> read(id));
  }

  // Plain MVCC reads: no row locks taken, never waits on reserve/cancel locks, never blocks them.
  // A seat locked by an uncommitted claim reads as available until that claim commits.
  // Never add FOR UPDATE / FOR SHARE here.
  private ShowView read(long id) {
    jdbc.sql("SET LOCAL statement_timeout = '1s'").update();
    var show =
        jdbc.sql("SELECT name, total_seats FROM shows WHERE id = ?")
            .param(id)
            .query((rs, n) -> Map.entry(rs.getString(1), rs.getInt(2)))
            .optional()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    var seats = new LinkedHashMap<String, List<String>>();
    STATUSES.forEach(st -> seats.put(st, new ArrayList<>()));
    jdbc.sql("SELECT status, seat_no FROM seats WHERE show_id = ? ORDER BY seat_no")
        .param(id)
        .query(
            rs -> {
              seats.get(rs.getString(1).toLowerCase(Locale.ROOT)).add(rs.getString(2));
            });
    var counts = new LinkedHashMap<String, Integer>();
    seats.forEach((st, list) -> counts.put(st, list.size()));
    return new ShowView(id, show.getKey(), show.getValue(), counts, seats);
  }

  record CreateShow(
      @NotBlank String name,
      @NotNull @Size(min = 1, max = 100_000)
          List<@NotNull @Pattern(regexp = "[A-Za-z0-9-]{1,99}") String> seats,
      @JsonProperty("price_paise") @NotNull @Positive Long pricePaise) {}

  @PostMapping("/shows")
  @ResponseStatus(HttpStatus.CREATED)
  Map<String, Long> create(@Valid @RequestBody CreateShow req, @AuthenticationPrincipal Jwt admin) {
    if (new HashSet<>(req.seats()).size() != req.seats().size()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "duplicate seat ids");
    }
    // explicit tx so the log line below runs after commit, not inside it
    long id = tx.execute(s -> insert(req));
    log.info(
        "show created id={} seats={} price_paise={} by={}",
        id,
        req.seats().size(),
        req.pricePaise(),
        admin.getSubject());
    return Map.of("id", id);
  }

  private long insert(CreateShow req) {
    long id =
        jdbc.sql("INSERT INTO shows (name, price_paise, total_seats) VALUES (?, ?, ?) RETURNING id")
            .params(req.name(), req.pricePaise(), req.seats().size())
            .query(Long.class)
            .single();
    // one statement, one round trip, whatever the seat count
    jdbc.sql("INSERT INTO seats (show_id, seat_no) SELECT ?, unnest(?::text[])")
        .params(id, req.seats().toArray(String[]::new))
        .update();
    return id;
  }
}
