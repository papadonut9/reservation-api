package dev.anchxt.reservationapi.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

public record ShowView(
    long id,
    String name,
    @JsonProperty("total_seats") int totalSeats,
    Map<String, Integer> counts,
    Map<String, List<String>> seats) {}
