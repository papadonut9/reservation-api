package dev.anchxt.reservationapi.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Dev-only token mint for the burst script and graders. No users table: any sub is accepted, so
 * this endpoint is full trust and must stay off (app.auth.dev-token-enabled=false) in real prod.
 */
@RestController
@ConditionalOnBooleanProperty("app.auth.dev-token-enabled")
class DevTokenController {

  private final JwtEncoder encoder;

  DevTokenController(JwtEncoder encoder) {
    this.encoder = encoder;
  }

  // sub capped at 128 to match the user_id CHECKs in schema.sql
  record TokenRequest(
      @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,128}") String sub,
      @Pattern(regexp = "USER|ADMIN") String role) {}

  @PostMapping("/auth/token")
  Map<String, String> token(@Valid @RequestBody TokenRequest req) {
    var now = Instant.now();
    var claims =
        JwtClaimsSet.builder()
            .subject(req.sub())
            .claim("role", req.role() == null ? "USER" : req.role())
            .issuedAt(now)
            .expiresAt(now.plus(Duration.ofHours(1)))
            .build();
    var header = JwsHeader.with(MacAlgorithm.HS256).build();
    return Map.of(
        "token", encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue());
  }
}
