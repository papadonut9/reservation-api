package dev.anchxt.reservationapi.service;

import dev.anchxt.reservationapi.dto.CreateShow;
import dev.anchxt.reservationapi.dto.ShowView;
import dev.anchxt.reservationapi.exception.NotFoundException;
import dev.anchxt.reservationapi.model.SeatStatus;
import dev.anchxt.reservationapi.model.Show;
import dev.anchxt.reservationapi.repository.ShowRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Transaction boundaries for shows. */
@Service
public class ShowService {

  private final ShowRepository repo;
  private final TransactionTemplate tx;
  private final TransactionTemplate snapshot;

  public ShowService(ShowRepository repo, TransactionTemplate tx) {
    this.repo = repo;
    this.tx = tx;
    // shared bean is mutable; own copy so GET's isolation never leaks into writes
    this.snapshot = new TransactionTemplate(tx.getTransactionManager());
    snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    snapshot.setReadOnly(true);
  }

  /** Seats and counts from one snapshot. */
  public ShowView get(long id) {
    return snapshot.execute(
        s -> {
          Show show = repo.findShow(id).orElseThrow(NotFoundException::new);
          var seats = new LinkedHashMap<String, List<String>>();
          for (var st : SeatStatus.values()) {
            seats.put(key(st), new ArrayList<>());
          }
          repo.findSeats(id)
              .forEach(seat -> seats.get(key(seat.getStatus())).add(seat.getId().seatNo()));
          var counts = new LinkedHashMap<String, Integer>();
          seats.forEach((st, list) -> counts.put(st, list.size()));
          return new ShowView(id, show.getName(), show.getTotalSeats(), counts, seats);
        });
  }

  /** Show row + every seat AVAILABLE, one transaction. Returns after commit. */
  public long create(CreateShow req) {
    // id and per_user_limit come from the DB (identity, DEFAULT 4); insert doesn't send them
    var show = new Show(null, req.name(), req.pricePaise(), 0, req.seats().size());
    return tx.execute(s -> repo.insert(show, req.seats()));
  }

  private static String key(SeatStatus st) {
    return st.name().toLowerCase(Locale.ROOT);
  }
}
