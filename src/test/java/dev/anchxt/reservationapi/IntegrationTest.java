package dev.anchxt.reservationapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Shared setup for integration tests. Keep the configuration identical across subclasses so Spring
 * reuses one context and one Postgres container for the whole run.
 */
@SpringBootTest(
    properties = {
      "app.jwt.secret=test-secret-at-least-32-bytes-long-0123456789",
      "app.auth.dev-token-enabled=true",
      "spring.flyway.enabled=true"
    })
@AutoConfigureMockMvc
@Import(IntegrationTest.Db.class)
abstract class IntegrationTest {

  @TestConfiguration(proxyBeanMethods = false)
  static class Db {
    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
      return new PostgreSQLContainer("postgres:16-alpine");
    }
  }

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  @Autowired PlatformTransactionManager txm;

  String token(String body) throws Exception {
    var json =
        mvc.perform(post("/auth/token").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return "Bearer " + JsonPath.read(json, "$.token");
  }

  String user(String sub) throws Exception {
    return token("{\"sub\":\"" + sub + "\"}");
  }

  static String key() {
    return UUID.randomUUID().toString();
  }

  String seatStatus(long show, String seat) {
    return jdbc.sql("SELECT status FROM seats WHERE show_id = ? AND seat_no = ?")
        .params(show, seat)
        .query(String.class)
        .single();
  }

  /** The quota row's count; 0 when no claim for this user and show ever committed. */
  int held(String sub, long show) {
    return jdbc.sql("SELECT held FROM user_show_quota WHERE user_id = ? AND show_id = ?")
        .params(sub, show)
        .query(Integer.class)
        .optional()
        .orElse(0);
  }

  ResultActions createShow(String body) throws Exception {
    return mvc.perform(
        post("/shows")
            .header("Authorization", token("{\"sub\":\"a1\",\"role\":\"ADMIN\"}"))
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
  }

  long newShow(int seats) throws Exception {
    var json =
        createShow(
                "{\"name\":\"s\",\"price_paise\":100,\"seats\":"
                    + IntStream.rangeClosed(1, seats)
                        .mapToObj(i -> "\"S" + i + "\"")
                        .collect(Collectors.joining(",", "[", "]"))
                    + "}")
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return ((Number) JsonPath.read(json, "$.id")).longValue();
  }

  ResultActions reserve(long show, String auth, String key, String... seats) throws Exception {
    return mvc.perform(
        post("/shows/" + show + "/reserve")
            .header("Authorization", auth)
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                Arrays.stream(seats)
                    .map(s -> "\"" + s + "\"")
                    .collect(
                        Collectors.joining(
                            ",", "{\"seats\":[", "],\"idempotency_key\":\"" + key + "\"}"))));
  }

  String getShow(long id, String auth) throws Exception {
    return mvc.perform(get("/shows/" + id).header("Authorization", auth))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  /** available + held + confirmed == total_seats, lists match counts, no seat in two lists. */
  static int assertInvariant(String json) {
    int total = JsonPath.read(json, "$.total_seats");
    int sum = 0;
    var all = new HashSet<String>();
    for (var st : List.of("available", "held", "confirmed")) {
      List<String> seats = JsonPath.read(json, "$.seats." + st);
      int count = JsonPath.read(json, "$.counts." + st);
      assertThat(seats).hasSize(count);
      all.addAll(seats);
      sum += count;
    }
    assertThat(sum).isEqualTo(total);
    assertThat(all).hasSize(total);
    return JsonPath.read(json, "$.counts.confirmed");
  }
}
