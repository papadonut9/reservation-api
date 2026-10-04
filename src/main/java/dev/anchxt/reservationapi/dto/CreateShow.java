package dev.anchxt.reservationapi.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

public record CreateShow(
    @NotBlank String name,
    @NotNull @Size(min = 1, max = 100_000)
        List<@NotNull @Pattern(regexp = "[A-Za-z0-9-]{1,99}") String> seats,
    @JsonProperty("price_paise") @NotNull @Positive Long pricePaise) {}
