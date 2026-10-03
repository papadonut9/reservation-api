package dev.anchxt.reservationapi;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
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

  @Test
  void contextLoads() {}
}
