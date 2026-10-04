package dev.anchxt.reservationapi.controller;

import dev.anchxt.reservationapi.dto.CreateShow;
import dev.anchxt.reservationapi.dto.ShowView;
import dev.anchxt.reservationapi.service.ShowService;
import jakarta.validation.Valid;
import java.util.HashSet;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** POST is admin-only (see SecurityConfig). GET is public (no token). */
@RestController
class ShowController {

  private static final Logger log = LoggerFactory.getLogger(ShowController.class);

  private final ShowService shows;

  ShowController(ShowService shows) {
    this.shows = shows;
  }

  @GetMapping("/shows/{id}")
  ShowView get(@PathVariable long id) {
    return shows.get(id);
  }

  @PostMapping("/shows")
  @ResponseStatus(HttpStatus.CREATED)
  Map<String, Long> create(@Valid @RequestBody CreateShow req, @AuthenticationPrincipal Jwt admin) {
    if (new HashSet<>(req.seats()).size() != req.seats().size()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "duplicate seat ids");
    }
    long id = shows.create(req); // committed by now; log I/O stays out of the tx
    log.info(
        "show created id={} seats={} price_paise={} by={}",
        id,
        req.seats().size(),
        req.pricePaise(),
        admin.getSubject());
    return Map.of("id", id);
  }
}
