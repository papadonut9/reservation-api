package dev.anchxt.reservationapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.transaction.support.TransactionTemplate;

@ExtendWith(OutputCaptureExtension.class)
class ReservationApiApplicationTests extends IntegrationTest {

  @Test
  void healthIsPublic() throws Exception {
    mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
  }

  @Test
  void noOrForgedTokenIs401(CapturedOutput out) throws Exception {
    mvc.perform(post("/shows")).andExpect(status().isUnauthorized());
    // HS256 signed with a different key
    var forged =
        "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ4Iiwicm9sZSI6IkFETUlOIn0."
            + "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
    mvc.perform(post("/shows").header("Authorization", "Bearer " + forged))
        .andExpect(status().isUnauthorized());
    assertThat(out)
        .contains("401 POST /shows: Full authentication")
        .contains("401 POST /shows: An error occurred while attempting to decode the Jwt");
  }

  @Test
  void postShowsIsAdminOnly(CapturedOutput out) throws Exception {
    mvc.perform(post("/shows").header("Authorization", token("{\"sub\":\"u1\"}")))
        .andExpect(status().isForbidden());
    assertThat(out).contains("403 POST /shows sub=u1");
  }

  @Test
  void createShowSeedsEverySeatAvailable() throws Exception {
    var seats =
        IntStream.rangeClosed(1, 5000)
            .mapToObj(i -> "\"" + (char) ('A' + i % 26) + i + "\"")
            .collect(Collectors.joining(",", "[", "]"));
    var json =
        createShow("{\"name\":\"big\",\"price_paise\":49900,\"seats\":" + seats + "}")
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    long id = ((Number) JsonPath.read(json, "$.id")).longValue();
    assertThat(
            jdbc.sql(
                    "SELECT count(*) FROM seats s JOIN shows sh ON sh.id = s.show_id"
                        + " WHERE s.show_id = ? AND s.status = 'AVAILABLE' AND sh.total_seats = 5000")
                .param(id)
                .query(Long.class)
                .single())
        .isEqualTo(5000L);
  }

  @Test
  void invalidShowIs400() throws Exception {
    for (var body :
        List.of(
            "{\"name\":\"x\",\"price_paise\":100,\"seats\":[\"A1\",\"A2\",\"A2\"]}",
            "{\"name\":\"x\",\"price_paise\":100,\"seats\":[]}",
            "{\"name\":\"x\",\"price_paise\":0,\"seats\":[\"A1\"]}",
            "{\"name\":\"x\",\"price_paise\":-5,\"seats\":[\"A1\"]}",
            "{\"name\":\"x\",\"price_paise\":100,\"seats\":[\"\"]}",
            "{\"name\":\"x\",\"price_paise\":100,\"seats\":[\"A 1\"]}",
            "{\"name\":\"x\",\"price_paise\":100,\"seats\":[\"" + "A".repeat(100) + "\"]}",
            "{\"name\":\"\",\"price_paise\":100,\"seats\":[\"A1\"]}")) {
      createShow(body).andExpect(status().isBadRequest());
    }
  }

  @Test
  void getShowGroupsSeatsByStatus() throws Exception {
    long id = newShow(3);
    reserve(id, token("{\"sub\":\"u1\"}"), "k", "S2").andExpect(status().isCreated());
    var json = getShow(id, token("{\"sub\":\"u1\"}"));
    assertThat((List<String>) JsonPath.read(json, "$.seats.available")).containsExactly("S1", "S3");
    assertThat((List<String>) JsonPath.read(json, "$.seats.held")).isEmpty();
    assertThat((List<String>) JsonPath.read(json, "$.seats.confirmed")).containsExactly("S2");
    assertThat(assertInvariant(json)).isEqualTo(1);

    mvc.perform(get("/shows/" + id)).andExpect(status().isOk());
    mvc.perform(get("/shows/999999")).andExpect(status().isNotFound());
  }

  @Test
  void invariantHoldsDuringAndAfterConcurrentReserves() throws Exception {
    long id = newShow(200);
    var auth = token("{\"sub\":\"u1\"}");
    // one user per reserve, so the per-user limit never turns a seat decline into a limit decline
    var users = new ArrayList<String>();
    for (int i = 0; i < 400; i++) {
      users.add(token("{\"sub\":\"inv" + i + "\"}"));
    }
    var wins = new AtomicInteger();
    var done = new AtomicBoolean();
    var pool = Executors.newFixedThreadPool(10);
    try {
      // 2 readers poll GET while 8 writers reserve: half the attempts on hot seat S1
      var readers =
          IntStream.range(0, 2)
              .mapToObj(
                  r ->
                      pool.submit(
                          () -> {
                            int reads = 0;
                            while (!done.get() || reads == 0) {
                              assertInvariant(getShow(id, auth));
                              reads++;
                            }
                            return reads;
                          }))
              .toList();
      var writers =
          IntStream.range(0, 400)
              .mapToObj(
                  i ->
                      pool.submit(
                          () -> {
                            int st =
                                reserve(
                                        id,
                                        users.get(i),
                                        UUID.randomUUID().toString(),
                                        i % 2 == 0 ? "S1" : "S" + (1 + i % 200))
                                    .andReturn()
                                    .getResponse()
                                    .getStatus();
                            assertThat(st).isIn(201, 409);
                            if (st == 201) {
                              wins.incrementAndGet();
                            }
                            return st;
                          }))
              .toList();
      for (var w : writers) w.get(30, TimeUnit.SECONDS);
      done.set(true);
      for (var r : readers) assertThat(r.get(30, TimeUnit.SECONDS)).isPositive();
    } finally {
      pool.shutdownNow();
    }
    assertThat(assertInvariant(getShow(id, auth))).isEqualTo(wins.get());
  }

  @Test
  void getDoesNotWaitOnSeatLock() throws Exception {
    long id = newShow(2);
    var locked = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var holder =
        Executors.newSingleThreadExecutor()
            .submit(
                () ->
                    new TransactionTemplate(txm)
                        .executeWithoutResult(
                            s -> {
                              jdbc.sql(
                                      "SELECT 1 FROM seats WHERE show_id = ? AND seat_no = 'S1' FOR UPDATE")
                                  .param(id)
                                  .query()
                                  .listOfRows();
                              locked.countDown();
                              try {
                                release.await(10, TimeUnit.SECONDS);
                              } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                              }
                            }));
    try {
      assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
      long start = System.nanoTime();
      var json = getShow(id, token("{\"sub\":\"u1\"}"));
      assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(1));
      assertThat((List<String>) JsonPath.read(json, "$.seats.available")).contains("S1");
    } finally {
      release.countDown();
      holder.get(10, TimeUnit.SECONDS);
    }
  }

  @Test
  void badTokenRequestIs400() throws Exception {
    mvc.perform(
            post("/auth/token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sub\":\"u1\",\"role\":\"ROOT\"}"))
        .andExpect(status().isBadRequest());
  }
}
