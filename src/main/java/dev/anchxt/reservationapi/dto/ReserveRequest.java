package dev.anchxt.reservationapi.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * No user field: identity comes from the token only, so a body {@code user_id} has nothing to bind
 * to. bad input is a 400, never a constraint violation.
 */
public record ReserveRequest(
    @NotNull @Size(min = 1, max = 100)
        List<@NotNull @Pattern(regexp = "[A-Za-z0-9-]{1,99}") String> seats,
    @JsonProperty("idempotency_key") @NotBlank @Size(max = 128) String idempotencyKey) {}
