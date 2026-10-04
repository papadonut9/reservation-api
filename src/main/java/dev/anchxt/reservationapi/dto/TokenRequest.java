package dev.anchxt.reservationapi.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

// sub capped at 128 to match the user_id CHECKs in schema.sql
public record TokenRequest(
    @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,128}") String sub,
    @Pattern(regexp = "USER|ADMIN") String role) {}
