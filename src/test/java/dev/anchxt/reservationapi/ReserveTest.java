package dev.anchxt.reservationapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import dev.anchxt.reservationapi.exception.ApiExceptionHandler;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

class ReserveTest extends IntegrationTest {

  @Autowired ApiExceptionHandler advice;
  @Autowired DataSource dataSource;
  @Autowired Semaphore admission;

  long reservations(long show) {
    return jdbc.sql("SELECT count(*) FROM reservations WHERE show_id = ?")
        .param(show)
        .query(Long.class)
        .single();
  }

  @Test
  void hotSeatHasExactlyOneWinner() throws Exception {
    long id = newShow(10);
    int n = 500;
    // tokens minted before the gate opens, so the storm is all reserves
    var tokens = new ArrayList<String>();
    for (int i = 0; i < n; i++) {
      tokens.add(user("hot" + i));
    }
    var gate = new CountDownLatch(1);
    var results = new ArrayList<Future<MockHttpServletResponse>>();
    try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
      for (var auth : tokens) {
        results.add(
            pool.submit(
                () -> {
                  gate.await();
                  return reserve(id, auth, key(), "S1").andReturn().getResponse();
                }));
      }
      gate.countDown();
      int created = 0;
      for (var f : results) {
        var r = f.get(30, TimeUnit.SECONDS);
        if (r.getStatus() == 201) {
          created++;
        } else {
          assertThat(r.getStatus()).isEqualTo(409);
          assertThat((String) JsonPath.read(r.getContentAsString(), "$.reason"))
              .isEqualTo("seat_taken");
        }
      }
      assertThat(created).isEqualTo(1);
    }
    assertThat(seatStatus(id, "S1")).isEqualTo("CONFIRMED");
    assertThat(reservations(id)).isEqualTo(1); // every loser rolled back its reservation row
  }

  @Test
  void bodyUserIdIsIgnored() throws Exception {
    long id = newShow(2);
    mvc.perform(
            post("/shows/" + id + "/reserve")
                .header("Authorization", user("real"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"seats\":[\"S1\"],\"idempotency_key\":\"k\",\"user_id\":\"victim\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.user_id").value("real"))
        .andExpect(jsonPath("$.show_id").value(id))
        .andExpect(jsonPath("$.seats[0]").value("S1"))
        .andExpect(jsonPath("$.amount_paise").value(100))
        .andExpect(jsonPath("$.status").value("confirmed"))
        .andExpect(jsonPath("$.reservation_id").isString());
    assertThat(
            jdbc.sql("SELECT user_id FROM reservations WHERE show_id = ?")
                .param(id)
                .query(String.class)
                .single())
        .isEqualTo("real");
  }

  @Test
  void partialRequestTakesNothing() throws Exception {
    long id = newShow(3);
    reserve(id, user("first"), key(), "S2").andExpect(status().isCreated());
    reserve(id, user("second"), key(), "S1", "S2")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.reason").value("seat_taken"));
    assertThat(seatStatus(id, "S1")).isEqualTo("AVAILABLE");
    assertThat(reservations(id)).isEqualTo(1);
  }

  @Test
  void duplicateSeatIsChargedOnce() throws Exception {
    long id = newShow(2);
    reserve(id, user("dup"), key(), "S1", "S1")
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.seats.length()").value(1))
        .andExpect(jsonPath("$.amount_paise").value(100));
  }

  @Test
  void seatsComeBackSorted() throws Exception {
    long id = newShow(3);
    reserve(id, user("sort"), key(), "S3", "S1")
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.seats[0]").value("S1"))
        .andExpect(jsonPath("$.seats[1]").value("S3"))
        .andExpect(jsonPath("$.amount_paise").value(200));
  }

  @Test
  void unknownSeatIs404AndTakesNothing() throws Exception {
    long id = newShow(2);
    reserve(id, user("unknown"), key(), "S1", "Z99").andExpect(status().isNotFound());
    assertThat(seatStatus(id, "S1")).isEqualTo("AVAILABLE");
    assertThat(reservations(id)).isZero();
  }

  @Test
  void unknownShowIs404() throws Exception {
    reserve(999_999, user("noshow"), key(), "S1").andExpect(status().isNotFound());
  }

  @Test
  void invalidRequestIs400() throws Exception {
    long id = newShow(1);
    var auth = user("bad");
    for (var body :
        List.of(
            "{\"seats\":[],\"idempotency_key\":\"k\"}",
            "{\"seats\":[\"A 1\"],\"idempotency_key\":\"k\"}",
            "{\"seats\":[\"S1\"]}",
            "{\"seats\":[\"S1\"],\"idempotency_key\":\" \"}",
            "{\"seats\":[\"S1\"],\"idempotency_key\":\"" + "k".repeat(129) + "\"}",
            "{\"idempotency_key\":\"k\"}")) {
      mvc.perform(
              post("/shows/" + id + "/reserve")
                  .header("Authorization", auth)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body))
          .andExpect(status().isBadRequest());
    }
    assertThat(reservations(id)).isZero();
  }

  @Test
  void noTokenIs401() throws Exception {
    mvc.perform(
            post("/shows/1/reserve")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"seats\":[\"S1\"],\"idempotency_key\":\"k\"}"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void lockTimeoutIsBusy() throws Exception {
    long id = newShow(1);
    var locked = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    // an open transaction holds the same (user, key) unique entry; reserve waits on it
    var holder =
        Executors.newSingleThreadExecutor()
            .submit(
                () ->
                    new TransactionTemplate(txm)
                        .executeWithoutResult(
                            s -> {
                              jdbc.sql(
                                      "INSERT INTO reservations (id, show_id, user_id,"
                                          + " idempotency_key, request_hash, amount_paise, status)"
                                          + " VALUES (?, ?, 'waiter', 'same', 'h', 100, 'CONFIRMED')")
                                  .params(UUID.randomUUID(), id)
                                  .update();
                              locked.countDown();
                              try {
                                release.await(10, TimeUnit.SECONDS);
                              } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                              }
                              s.setRollbackOnly();
                            }));
    try {
      assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
      reserve(id, user("waiter"), "same", "S1")
          .andExpect(status().isConflict())
          .andExpect(jsonPath("$.reason").value("busy"));
    } finally {
      release.countDown();
      holder.get(10, TimeUnit.SECONDS);
    }
    assertThat(seatStatus(id, "S1")).isEqualTo("AVAILABLE");
  }

  @Test
  void statementTimeoutIsBusy() {
    // the real exception this Spring/Hibernate/PgJDBC stack throws for 57014, through the advice
    RuntimeException e =
        assertThrows(
            RuntimeException.class,
            () ->
                new TransactionTemplate(txm)
                    .executeWithoutResult(
                        s -> {
                          jdbc.sql("SET LOCAL statement_timeout = '1ms'").update();
                          jdbc.sql("SELECT pg_sleep(1)").query().singleRow();
                        }));
    var response = advice.database(e, new MockHttpServletRequest());
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(response.getBody()).isEqualTo(Map.of("reason", "busy"));
  }

  @Test
  void poolExhaustionIs429() throws Exception {
    long id = newShow(1);
    var auth = user("starved");
    int permits = admission.availablePermits();
    var held = new ArrayList<Connection>();
    try {
      // the reserve gets a permit, then times out waiting on the pool
      for (int i = 0; i < 15; i++) {
        held.add(dataSource.getConnection());
      }
      reserve(id, auth, key(), "S1")
          .andExpect(status().isTooManyRequests())
          .andExpect(jsonPath("$.reason").value("overloaded"));
    } finally {
      for (var c : held) {
        c.close();
      }
    }
    assertThat(seatStatus(id, "S1")).isEqualTo("AVAILABLE");
    assertThat(admission.availablePermits()).isEqualTo(permits);
  }

  static String body(ResultActions r) throws Exception {
    return r.andReturn().getResponse().getContentAsString();
  }

  @Test
  void retryReplaysOriginalAndMovesNothing() throws Exception {
    long id = newShow(3);
    var auth = user("retry");
    var first = body(reserve(id, auth, "k1", "S2", "S1").andExpect(status().isCreated()));
    var again = body(reserve(id, auth, "k1", "S1", "S2").andExpect(status().isCreated()));
    assertThat(again).isEqualTo(first); // same seats in another order is the same request
    assertThat(reservations(id)).isEqualTo(1);
    assertThat(seatStatus(id, "S3")).isEqualTo("AVAILABLE");
  }

  @Test
  void sameKeyDifferentRequestIs409() throws Exception {
    long id = newShow(3);
    long other = newShow(1);
    var auth = user("changed");
    reserve(id, auth, "k1", "S1").andExpect(status().isCreated());
    reserve(id, auth, "k1", "S2")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.reason").value("idempotency_conflict"));
    reserve(other, auth, "k1", "S1")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.reason").value("idempotency_conflict"));
    assertThat(seatStatus(id, "S2")).isEqualTo("AVAILABLE");
    assertThat(seatStatus(other, "S1")).isEqualTo("AVAILABLE");
  }

  @Test
  void declinedKeyIsNotStored() throws Exception {
    long id = newShow(2);
    reserve(id, user("owner"), key(), "S1").andExpect(status().isCreated());
    var auth = user("late");
    reserve(id, auth, "k1", "S1").andExpect(status().isConflict());
    // the decline rolled back, so k1 is free: a retry runs fresh, a new body is not a conflict
    reserve(id, auth, "k1", "S1")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.reason").value("seat_taken"));
    reserve(id, auth, "k1", "S2").andExpect(status().isCreated());
  }

  @Test
  void keysAreScopedPerUser() throws Exception {
    long id = newShow(2);
    reserve(id, user("a"), "shared", "S1").andExpect(status().isCreated());
    reserve(id, user("b"), "shared", "S2").andExpect(status().isCreated());
    assertThat(reservations(id)).isEqualTo(2);
  }

  @Test
  void parallelSameKeyReservesOnce() throws Exception {
    long id = newShow(2);
    var auth = user("storm");
    var gate = new CountDownLatch(1);
    var results = new ArrayList<Future<MockHttpServletResponse>>();
    try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < 50; i++) {
        results.add(
            pool.submit(
                () -> {
                  gate.await();
                  return reserve(id, auth, "same", "S1").andReturn().getResponse();
                }));
      }
      gate.countDown();
      var ids = new HashSet<String>();
      for (var f : results) {
        var r = f.get(30, TimeUnit.SECONDS);
        if (r.getStatus() == 201) {
          ids.add(JsonPath.read(r.getContentAsString(), "$.reservation_id"));
        } else {
          // a waiter on the unique index can outlast lock_timeout on a slow runner
          assertThat(r.getStatus()).isEqualTo(409);
          assertThat((String) JsonPath.read(r.getContentAsString(), "$.reason")).isEqualTo("busy");
        }
      }
      assertThat(ids).hasSize(1);
    }
    assertThat(reservations(id)).isEqualTo(1);
    assertThat(seatStatus(id, "S2")).isEqualTo("AVAILABLE");
  }

  @Test
  void admissionTimeoutIs429AndLeaksNoPermit() throws Exception {
    long id = newShow(1);
    var auth = user("queued");
    int permits = admission.availablePermits();
    admission.acquire(permits); // drain: the reserve's own tryAcquire times out
    try {
      reserve(id, auth, key(), "S1")
          .andExpect(status().isTooManyRequests())
          .andExpect(jsonPath("$.reason").value("overloaded"));
    } finally {
      admission.release(permits);
    }
    // a release after the failed tryAcquire would show up here as permits + 1
    assertThat(admission.availablePermits()).isEqualTo(permits);
    assertThat(reservations(id)).isZero();
  }

  int confirmedSeats(String sub, long show) {
    return jdbc.sql(
            "SELECT count(*) FROM seats s JOIN reservations r ON r.id = s.reservation_id"
                + " WHERE r.user_id = ? AND s.show_id = ?")
        .params(sub, show)
        .query(Integer.class)
        .single();
  }

  @Test
  void limitIsExactSequentially() throws Exception {
    long id = newShow(6); // per_user_limit defaults to 4
    var auth = user("seq");
    for (int i = 1; i <= 4; i++) {
      reserve(id, auth, "k" + i, "S" + i).andExpect(status().isCreated());
    }
    reserve(id, auth, "k5", "S5")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.reason").value("per_user_limit"));
    // a replay moves nothing, so it still answers at the limit
    reserve(id, auth, "k1", "S1").andExpect(status().isCreated());
    assertThat(held("seq", id)).isEqualTo(4);
    assertThat(seatStatus(id, "S5")).isEqualTo("AVAILABLE");
  }

  @Test
  void multiSeatRequestCountsEverySeat() throws Exception {
    long id = newShow(6);
    var auth = user("multi");
    reserve(id, auth, key(), "S1", "S2", "S3").andExpect(status().isCreated());
    reserve(id, auth, key(), "S4", "S5")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.reason").value("per_user_limit"));
    assertThat(seatStatus(id, "S4")).isEqualTo("AVAILABLE");
    reserve(id, auth, key(), "S4").andExpect(status().isCreated());
    assertThat(held("multi", id)).isEqualTo(4);
  }

  @Test
  void firstRequestOverLimitIsDeclined() throws Exception {
    long id = newShow(6);
    reserve(id, user("greedy"), key(), "S1", "S2", "S3", "S4", "S5")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.reason").value("per_user_limit"));
    assertThat(held("greedy", id)).isZero();
    assertThat(reservations(id)).isZero();
  }

  @Test
  void failedSeatClaimGivesQuotaBack() throws Exception {
    long id = newShow(6);
    reserve(id, user("other"), key(), "S1").andExpect(status().isCreated());
    var auth = user("unlucky");
    reserve(id, auth, key(), "S2", "S3", "S4").andExpect(status().isCreated());
    reserve(id, auth, key(), "S1")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.reason").value("seat_taken"));
    assertThat(held("unlucky", id)).isEqualTo(3); // the seat_taken rollback undid its +1
    reserve(id, auth, key(), "S5").andExpect(status().isCreated());
    assertThat(held("unlucky", id)).isEqualTo(4);
  }

  @Test
  void parallelRequestsStayWithinLimit() throws Exception {
    long id = newShow(10);
    var auth = user("burst");
    var gate = new CountDownLatch(1);
    var results = new ArrayList<Future<MockHttpServletResponse>>();
    try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 1; i <= 10; i++) {
        var seat = "S" + i;
        results.add(
            pool.submit(
                () -> {
                  gate.await();
                  return reserve(id, auth, key(), seat).andReturn().getResponse();
                }));
      }
      gate.countDown();
      int created = 0;
      for (var f : results) {
        var r = f.get(30, TimeUnit.SECONDS);
        if (r.getStatus() == 201) {
          created++;
        } else {
          // waiters queue on the quota row and can outlast lock_timeout on a slow runner
          assertThat(r.getStatus()).isEqualTo(409);
          assertThat((String) JsonPath.read(r.getContentAsString(), "$.reason"))
              .isIn("per_user_limit", "busy");
        }
      }
      assertThat(created).isBetween(1, 4);
      assertThat(confirmedSeats("burst", id)).isEqualTo(created);
    }
    assertThat(held("burst", id)).isEqualTo(confirmedSeats("burst", id));
    assertInvariant(getShow(id, auth));
  }

  @Test
  void overlappingMultiSeatRequestsAreAllOrNothing() throws Exception {
    long id = newShow(6);
    int n = 300;
    // each request wants two neighbours on a ring of 6 seats, every other one in reverse order:
    // [S1,S2] vs [S2,S3] vs ... vs [S6,S1], the overlap an unordered lock would deadlock on
    var requests = new ArrayList<String[]>();
    var tokens = new ArrayList<String>();
    for (int i = 0; i < n; i++) {
      var a = "S" + (1 + i % 6);
      var b = "S" + (1 + (i + 1) % 6);
      requests.add(i % 2 == 0 ? new String[] {a, b} : new String[] {b, a});
      tokens.add(user("ring" + i));
    }
    var gate = new CountDownLatch(1);
    var results = new ArrayList<Future<MockHttpServletResponse>>();
    try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < n; i++) {
        var auth = tokens.get(i);
        var seats = requests.get(i);
        results.add(
            pool.submit(
                () -> {
                  gate.await();
                  return reserve(id, auth, key(), seats).andReturn().getResponse();
                }));
      }
      gate.countDown();
      var owner = new HashMap<String, String>(); // seat -> reservation_id of the 201 holding it
      for (var f : results) {
        var r = f.get(30, TimeUnit.SECONDS);
        var json = r.getContentAsString();
        if (r.getStatus() == 201) {
          List<String> seats = JsonPath.read(json, "$.seats");
          assertThat(seats).hasSize(2); // a success always holds the whole request
          for (var seat : seats) {
            assertThat(owner.put(seat, JsonPath.read(json, "$.reservation_id"))).isNull();
          }
        } else {
          // distinct users and keys never wait, so no busy: a deadlock would show up here
          assertThat(r.getStatus()).isEqualTo(409);
          assertThat((String) JsonPath.read(json, "$.reason")).isEqualTo("seat_taken");
        }
      }
      assertThat(owner).isNotEmpty();
      // the DB agrees with the 201s seat for seat, and every loser left nothing behind
      var confirmed = new HashMap<String, String>();
      jdbc.sql(
              "SELECT seat_no, reservation_id::text FROM seats"
                  + " WHERE show_id = ? AND status = 'CONFIRMED'")
          .param(id)
          .query(
              rs -> {
                confirmed.put(rs.getString(1), rs.getString(2));
              });
      assertThat(confirmed).isEqualTo(owner);
      assertThat(reservations(id)).isEqualTo(owner.size() / 2);
    }
    assertInvariant(getShow(id, tokens.get(0)));
  }
}
