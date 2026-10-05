package dev.anchxt.reservationapi.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Correlation id for every request, ahead of Spring Security so 401/403 carry one too. An inbound
 * {@code X-Request-Id} is kept only if it is short and plain (no log injection, bounded size);
 * otherwise a UUID is minted. The id goes into the MDC (every log line of the request) and back in
 * the response header. One access line per request, never a metric tag.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

  private static final String HEADER = "X-Request-Id";

  private static final Logger log = LoggerFactory.getLogger(RequestIdFilter.class);
  private static final Pattern VALID = Pattern.compile("[A-Za-z0-9-]{8,64}");

  @Override
  protected void doFilterInternal(
      HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws ServletException, IOException {
    var inbound = req.getHeader(HEADER);
    var id =
        inbound != null && VALID.matcher(inbound).matches()
            ? inbound
            : UUID.randomUUID().toString();
    MDC.put("requestId", id);
    res.setHeader(HEADER, id); // before the chain: the response may be committed inside it
    long start = System.nanoTime();
    try {
      chain.doFilter(req, res);
    } finally {
      log.info(
          "{} {} {} {}ms",
          req.getMethod(),
          req.getRequestURI(),
          res.getStatus(),
          (System.nanoTime() - start) / 1_000_000);
      MDC.remove("requestId");
    }
  }
}
