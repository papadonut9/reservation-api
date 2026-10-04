package dev.anchxt.reservationapi.config;

import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class AdmissionConfig {

  /**
   * Bound concurrent reserve transactions below the pool size, so reserves never wait on the pool
   * and two connections stay free for GET /shows and the readiness check. Fair: under heavy load,
   * requests are admitted in arrival order.
   */
  @Bean
  Semaphore reserveAdmission(@Value("${spring.datasource.hikari.maximum-pool-size:10}") int pool) {
    return new Semaphore(Math.max(1, pool - 2), true);
  }
}
