package dev.anchxt.reservationapi.show;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Admin-only (see SecurityConfig). Show row + every seat AVAILABLE, in one transaction. */
@RestController
class ShowController {

  private static final Logger log = LoggerFactory.getLogger(ShowController.class);

  private final JdbcClient jdbc;
  private final TransactionTemplate tx;

  ShowController(JdbcClient jdbc, TransactionTemplate tx) {
    this.jdbc = jdbc;
    this.tx = tx;
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
