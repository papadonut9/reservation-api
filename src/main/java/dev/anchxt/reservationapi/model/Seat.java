package dev.anchxt.reservationapi.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Row of {@code seats}: one per seat, status is the only truth (no counter columns). Mapped for
 * schema validation; claims are atomic conditional UPDATEs in JdbcClient, never load-then-save.
 */
@Entity
@Table(name = "seats")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class Seat {

  @Embeddable
  public record Id(
      @Column(name = "show_id", nullable = false) Long showId,
      @Column(name = "seat_no", nullable = false) String seatNo) {}

  @EmbeddedId private Id id;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private SeatStatus status;

  @Column(name = "reservation_id")
  private UUID reservationId;
}
