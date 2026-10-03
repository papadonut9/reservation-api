package dev.anchxt.reservationapi;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(
    properties = {
      "app.jwt.secret=test-secret-at-least-32-bytes-long-0123456789",
      "app.auth.dev-token-enabled=true"
    })
@AutoConfigureMockMvc
@Import(ReservationApiApplicationTests.Db.class)
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
  void noOrForgedTokenIs401() throws Exception {
    mvc.perform(post("/shows")).andExpect(status().isUnauthorized());
    // HS256 signed with a different key
    var forged =
        "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ4Iiwicm9sZSI6IkFETUlOIn0."
            + "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
    mvc.perform(post("/shows").header("Authorization", "Bearer " + forged))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void postShowsIsAdminOnly() throws Exception {
    mvc.perform(post("/shows").header("Authorization", token("{\"sub\":\"u1\"}")))
        .andExpect(status().isForbidden());
    // passes authz; 404 until the shows endpoint exists
    mvc.perform(
            post("/shows").header("Authorization", token("{\"sub\":\"a1\",\"role\":\"ADMIN\"}")))
        .andExpect(status().isNotFound());
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
