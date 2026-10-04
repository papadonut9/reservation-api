package dev.anchxt.reservationapi.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Row of {@code shows}. Mapped for schema validation; reads/writes go through JdbcClient. */
@Entity
@Table(name = "shows")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class Show {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false)
  private String name;

  @Column(name = "price_paise", nullable = false)
  private long pricePaise;

  @Column(name = "per_user_limit", nullable = false)
  private int perUserLimit;

  @Column(name = "total_seats", nullable = false)
  private int totalSeats;
}
