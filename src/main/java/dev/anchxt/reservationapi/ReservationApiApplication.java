package dev.anchxt.reservationapi;

import java.util.TimeZone;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ReservationApiApplication {

  public static void main(String[] args) {
    // pgjdbc sends the JVM zone at connect; Windows reports legacy "Asia/Calcutta",
    // which some Postgres builds reject. Server runs in UTC regardless of host.
    TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"));
    SpringApplication.run(ReservationApiApplication.class, args);
  }
}
