package dev.anchxt.reservationapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

class CancelTest extends IntegrationTest {

  ResultActions cancel(String id, String auth) throws Exception {
    return mvc.perform(post("/reservations/" + id + "/cancel").header("Authorization", auth));
  }

  String reserveId(long show, String auth, String key, String... seats) throws Exception {
    var json =
        reserve(show, auth, key, seats)
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return JsonPath.read(json, "$.reservation_id");
  }

  String owner(long show, String seat) {
    return jdbc.sql("SELECT reservation_id::text FROM seats WHERE show_id = ? AND seat_no = ?")
        .params(show, seat)
        .query(String.class)
        .single();
  }

  @Test
  void releasedSeatsAreRebookable() throws Exception {
    long id = newShow(3);
    var a = user("a");
    var rid = reserveId(id, a, key(), "S1", "S2");
    cancel(rid, a).andExpect(status().isNoContent());
    assertThat(seatStatus(id, "S1")).isEqualTo("AVAILABLE");
    assertThat(seatStatus(id, "S2")).isEqualTo("AVAILABLE");
    assertThat(held("a", id)).isZero();
    reserve(id, user("b"), key(), "S1", "S2").andExpect(status().isCreated());
    assertInvariant(getShow(id, a));
  }

  @Test
  void repeatCancelNeverFreesSomeoneElsesSeat() throws Exception {
    long id = newShow(2);
    var a = user("first");
    var rid = reserveId(id, a, key(), "S1");
    cancel(rid, a).andExpect(status().isNoContent());
    var bRid = reserveId(id, user("second"), key(), "S1");
    // the old id no longer owns S1: a repeat cancel is a no-op, not a release
    cancel(rid, a).andExpect(status().isNoContent());
    assertThat(seatStatus(id, "S1")).isEqualTo("CONFIRMED");
    assertThat(owner(id, "S1")).isEqualTo(bRid);
    assertThat(held("first", id)).isZero(); // not decremented twice
    assertThat(held("second", id)).isEqualTo(1);
  }

  @Test
  void otherUsersReservationIs404AndUntouched() throws Exception {
    long id = newShow(2);
    var rid = reserveId(id, user("victim"), key(), "S1");
    cancel(rid, user("attacker")).andExpect(status().isNotFound());
    assertThat(seatStatus(id, "S1")).isEqualTo("CONFIRMED");
    assertThat(held("victim", id)).isEqualTo(1);
  }

  @Test
  void unknownIdIs404AndBadIdIs400() throws Exception {
    var auth = user("nobody");
    cancel(UUID.randomUUID().toString(), auth).andExpect(status().isNotFound());
    cancel("not-a-uuid", auth).andExpect(status().isBadRequest());
  }

  @Test
  void noTokenIs401() throws Exception {
    mvc.perform(post("/reservations/" + UUID.randomUUID() + "/cancel"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void cancelGivesQuotaBack() throws Exception {
    long id = newShow(6);
    var auth = user("full");
    var rid = reserveId(id, auth, key(), "S1", "S2", "S3", "S4");
    reserve(id, auth, key(), "S5")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.reason").value("per_user_limit"));
    cancel(rid, auth).andExpect(status().isNoContent());
    reserve(id, auth, key(), "S5").andExpect(status().isCreated());
    assertThat(held("full", id)).isEqualTo(1);
  }

  @Test
  void replayAfterCancelShowsCancelledAndMovesNothing() throws Exception {
    long id = newShow(2);
    var auth = user("replayer");
    var rid = reserveId(id, auth, "k1", "S1");
    cancel(rid, auth).andExpect(status().isNoContent());
    reserve(id, auth, "k1", "S1")
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.reservation_id").value(rid))
        .andExpect(jsonPath("$.seats[0]").value("S1"))
        .andExpect(jsonPath("$.status").value("cancelled"));
    assertThat(seatStatus(id, "S1")).isEqualTo("AVAILABLE");
    assertThat(held("replayer", id)).isZero();
  }
}
