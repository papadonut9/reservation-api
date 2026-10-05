package dev.anchxt.reservationapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/** Every response carries an X-Request-Id; only a plain inbound id is trusted. */
class RequestIdTest extends IntegrationTest {

  static final String UUID = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

  @Test
  void rejectedBySecurityStillGetsAnId() throws Exception {
    mvc.perform(post("/shows/1/reserve"))
        .andExpect(status().isUnauthorized())
        .andExpect(header().string("X-Request-Id", matchesPattern(UUID)));
    assertThat(MDC.get("requestId")).as("MDC cleared after the request").isNull();
  }

  @Test
  void plainInboundIdIsEchoed() throws Exception {
    long show = newShow(1);
    var auth = user("rid");
    mvc.perform(
            post("/shows/" + show + "/reserve")
                .header("X-Request-Id", "burst-0001-abc")
                .header("Authorization", auth)
                .contentType("application/json")
                .content("{\"seats\":[\"S1\"],\"idempotency_key\":\"" + key() + "\"}"))
        .andExpect(status().isCreated())
        .andExpect(header().string("X-Request-Id", "burst-0001-abc"));
  }

  @Test
  void unsafeOrOversizedInboundIdIsReplaced() throws Exception {
    for (var bad : new String[] {"short", "a b\tc=injected-1", "x".repeat(65)}) {
      mvc.perform(post("/shows/1/reserve").header("X-Request-Id", bad))
          .andExpect(header().string("X-Request-Id", matchesPattern(UUID)));
    }
  }
}
