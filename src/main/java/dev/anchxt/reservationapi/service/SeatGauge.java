package dev.anchxt.reservationapi.service;

import dev.anchxt.reservationapi.repository.ShowRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * {@code seats{show_id,status}} read from the seats table on a schedule, never counted in memory,
 * so it always matches what GET /shows/{id} reports. Up to 5s stale.
 */
@Component
public class SeatGauge {

  private final ShowRepository repo;
  private final MultiGauge seats;

  public SeatGauge(ShowRepository repo, MeterRegistry meters) {
    this.repo = repo;
    this.seats = MultiGauge.builder("seats").register(meters);
  }

  @Scheduled(fixedDelay = 5, timeUnit = TimeUnit.SECONDS)
  public void refresh() {
    seats.register(
        repo.countSeats().stream()
            .map(
                c ->
                    MultiGauge.Row.of(
                        Tags.of(
                            "show_id",
                            Long.toString(c.showId()),
                            "status",
                            c.status().toLowerCase(Locale.ROOT)),
                        c.count()))
            .toList(),
        true);
  }
}
