package dev.anchxt.reservationapi.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.UUID;

public record ReservationView(
    @JsonProperty("reservation_id") UUID reservationId,
    @JsonProperty("show_id") long showId,
    @JsonProperty("user_id") String userId,
    List<String> seats,
    @JsonProperty("amount_paise") long amountPaise,
    String status) {}
