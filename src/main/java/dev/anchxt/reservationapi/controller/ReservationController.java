package dev.anchxt.reservationapi.controller;

import dev.anchxt.reservationapi.dto.ReservationView;
import dev.anchxt.reservationapi.dto.ReserveRequest;
import dev.anchxt.reservationapi.service.ReservationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Any authenticated user (see SecurityConfig). Acts only as the token's subject. */
@RestController
class ReservationController {

  private final ReservationService reservations;

  ReservationController(ReservationService reservations) {
    this.reservations = reservations;
  }

  @PostMapping("/shows/{id}/reserve")
  @ResponseStatus(HttpStatus.CREATED)
  ReservationView reserve(
      @PathVariable long id,
      @Valid @RequestBody ReserveRequest req,
      @AuthenticationPrincipal Jwt user) {
    return reservations.reserve(id, user.getSubject(), req.seats(), req.idempotencyKey());
  }
}
