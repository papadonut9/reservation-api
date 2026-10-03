package dev.anchxt.reservationapi.auth;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;

@Configuration
public class SecurityConfig {

  private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

  // HS256 needs >= 32 bytes; Nimbus rejects shorter keys, so a weak secret fails startup.
  private final SecretKey key;

  SecurityConfig(@Value("${app.jwt.secret}") String secret) {
    this.key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
  }

  @Bean
  SecurityFilterChain api(HttpSecurity http) {
    // auth failures stop in the filter chain, before any controller: log them here.
    // Never log the Authorization header; the exception message names the reason only.
    var bearer401 = new BearerTokenAuthenticationEntryPoint();
    AuthenticationEntryPoint unauthorized =
        (req, res, ex) -> {
          log.warn("401 {} {}: {}", req.getMethod(), req.getRequestURI(), ex.getMessage());
          bearer401.commence(req, res, ex);
        };
    var bearer403 = new BearerTokenAccessDeniedHandler();
    AccessDeniedHandler forbidden =
        (req, res, ex) -> {
          log.warn(
              "403 {} {} sub={}",
              req.getMethod(),
              req.getRequestURI(),
              Objects.requireNonNull(SecurityContextHolder.getContext().getAuthentication()).getName());
          bearer403.handle(req, res, ex);
        };
    return http.csrf(AbstractHttpConfigurer::disable)
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            a ->
                a.requestMatchers(
                        "/actuator/health/**", "/actuator/prometheus", "/auth/token", "/error")
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, "/shows")
                    .hasRole("ADMIN")
                    .anyRequest()
                    .authenticated())
        // missing token / wrong role
        .exceptionHandling(
            e -> e.authenticationEntryPoint(unauthorized).accessDeniedHandler(forbidden))
        // invalid / expired / forged token
        .oauth2ResourceServer(
            o ->
                o.jwt(Customizer.withDefaults())
                    .authenticationEntryPoint(unauthorized)
                    .accessDeniedHandler(forbidden))
        .build();
  }

  @Bean
  JwtDecoder jwtDecoder() {
    return NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
  }

  @Bean
  JwtEncoder jwtEncoder() {
    return new NimbusJwtEncoder(new ImmutableSecret<>(key));
  }
}
