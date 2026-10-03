package dev.anchxt.reservationapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(
    properties = {
      "app.jwt.secret=test-secret-at-least-32-bytes-long-0123456789",
      "app.auth.dev-token-enabled=true",
      // local, gitignored application-dev.yml may turn Flyway off; tests always migrate
      "spring.flyway.enabled=true"
    })
@AutoConfigureMockMvc
@Import(ReservationApiApplicationTests.Db.class)
@ExtendWith(OutputCaptureExtension.class)
class ReservationApiApplicationTests {

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

  String token(String body) throws Exception {
    var json =
        mvc.perform(post("/auth/token").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return "Bearer " + JsonPath.read(json, "$.token");
  }

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

  ResultActions createShow(String body) throws Exception {
    return mvc.perform(
        post("/shows")
            .header("Authorization", token("{\"sub\":\"a1\",\"role\":\"ADMIN\"}"))
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
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
  void badTokenRequestIs400() throws Exception {
    mvc.perform(
            post("/auth/token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sub\":\"u1\",\"role\":\"ROOT\"}"))
        .andExpect(status().isBadRequest());
  }
}
