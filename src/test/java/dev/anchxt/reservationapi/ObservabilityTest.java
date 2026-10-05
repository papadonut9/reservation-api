package dev.anchxt.reservationapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import dev.anchxt.reservationapi.service.SeatGauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Probes and metrics, public and without a token. */
class ObservabilityTest extends IntegrationTest {

  @Autowired MeterRegistry meters;
  @Autowired SeatGauge seatGauge;

  @Test
  void livenessIgnoresTheDatabase() throws Exception {
    mvc.perform(get("/actuator/health/liveness"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"))
        .andExpect(jsonPath("$.components.db").doesNotExist());
  }

  @Test
  void readinessChecksTheDatabase() throws Exception {
    mvc.perform(get("/actuator/health/readiness"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"))
        .andExpect(jsonPath("$.components.db.status").value("UP"));
  }

  /** One of each outcome moves exactly its own counter; a replay is never a second confirm. */
  @Test
  void everyReserveOutcomeIsCountedOnce() throws Exception {
    long show = newShow(6);
    var a = user("obs-a");
    var b = user("obs-b");
    var before = outcomes();
    var k = key();

    reserve(show, a, k, "S1").andExpect(status().isCreated());
    reserve(show, a, k, "S1").andExpect(status().isCreated());
    reserve(show, a, k, "S2").andExpect(status().isConflict());
    reserve(show, b, key(), "S1").andExpect(status().isConflict());
    reserve(show, b, key(), "S2", "S3", "S4", "S5", "S6").andExpect(status().isConflict());

    var after = outcomes();
    after.replaceAll((k2, v) -> v - before.get(k2));
    assertThat(after)
        .isEqualTo(
            Map.of(
                "confirmed", 1.0,
                "seat_taken", 1.0,
                "per_user_limit", 1.0,
                "idempotent_replay", 1.0,
                "idempotency_conflict", 1.0,
                "busy", 0.0,
                "overloaded", 0.0));
  }

  @Test
  void prometheusIsPublicAndCarriesTheCounters() throws Exception {
    // http_server_requests is recorded when a request completes, so complete one first
    mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
    var body =
        mvc.perform(get("/actuator/prometheus"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(body)
        .contains(
            "reservations_confirmed_total",
            "reservations_declined_total{reason=\"idempotent_replay\"}",
            "hikaricp_connections_active",
            "http_server_requests_seconds");
  }

  /** The gauge reports what GET /shows reports, zeros included. */
  @Test
  void seatGaugeMatchesTheShow() throws Exception {
    long show = newShow(3);
    reserve(show, user("obs-g"), key(), "S1").andExpect(status().isCreated());
    seatGauge.refresh();
    var json = getShow(show, user("obs-g"));
    for (var st : List.of("available", "held", "confirmed")) {
      int count = JsonPath.read(json, "$.counts." + st);
      assertThat(
              meters
                  .get("seats")
                  .tags("show_id", Long.toString(show), "status", st)
                  .gauge()
                  .value())
          .as(st)
          .isEqualTo(count);
    }
  }

  private Map<String, Double> outcomes() {
    var m = new TreeMap<String, Double>();
    m.put("confirmed", meters.get("reservations.confirmed").counter().count());
    meters
        .get("reservations.declined")
        .counters()
        .forEach(c -> m.put(c.getId().getTag("reason"), c.count()));
    return m;
  }
}
