package dev.anchxt.reservationapi.exception;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import java.net.ConnectException;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Domain outcomes are 4xx; 5xx only when the database itself is unreachable or something is truly
 * broken.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

  // lock_not_available (lock_timeout / NOWAIT), query_canceled (statement_timeout),
  // deadlock_detected (unreachable with the fixed lock order; mapped as a backstop)
  private static final Set<String> BUSY = Set.of("55P03", "57014", "40P01");

  private final MeterRegistry meters;

  public ApiExceptionHandler(MeterRegistry meters) {
    this.meters = meters;
    // every series exists from startup at 0, so increase() sees the first decline
    for (var r :
        List.of(
            "seat_taken",
            "per_user_limit",
            "idempotent_replay",
            "idempotency_conflict",
            "busy",
            "overloaded")) {
      meters.counter("reservations.declined", "reason", r);
    }
  }

  @ExceptionHandler(ConflictException.class)
  ResponseEntity<Map<String, String>> conflict(ConflictException e, HttpServletRequest req) {
    return declined(req, HttpStatus.CONFLICT, e.getMessage());
  }

  @ExceptionHandler(OverloadedException.class)
  ResponseEntity<Map<String, String>> overloaded(OverloadedException e, HttpServletRequest req) {
    return declined(req, HttpStatus.TOO_MANY_REQUESTS, e.getMessage());
  }

  @ExceptionHandler({DataAccessException.class, TransactionException.class})
  public ResponseEntity<Map<String, String>> database(RuntimeException e, HttpServletRequest req) {
    for (Throwable t = e; t != null; t = t.getCause()) {
      // Hikari pool timeout: carries the last connection failure (and its 08xxx state) when the
      // DB is down; no such failure means every connection is simply busy
      if (t instanceof SQLTransientConnectionException c) {
        return dbDown(c)
            ? unavailable(e)
            : declined(req, HttpStatus.TOO_MANY_REQUESTS, "overloaded");
      }
      if (t instanceof SQLException s && s.getSQLState() != null) {
        if (BUSY.contains(s.getSQLState())) {
          if ("40P01".equals(s.getSQLState())) {
            log.warn("deadlock despite fixed lock order", e);
          }
          return declined(req, HttpStatus.CONFLICT, "busy");
        }
        if (s.getSQLState().startsWith("08")) {
          return unavailable(e);
        }
      }
    }
    // backstop for a lock or timeout failure that arrives without the driver's SQLException
    if (e instanceof PessimisticLockingFailureException || e instanceof QueryTimeoutException) {
      return declined(req, HttpStatus.CONFLICT, "busy");
    }
    if (e instanceof DataIntegrityViolationException) {
      return reason(HttpStatus.CONFLICT, "conflict");
    }
    log.error("unmapped database error", e);
    return reason(HttpStatus.INTERNAL_SERVER_ERROR, "internal");
  }

  /**
   * Counted here, after the transaction has rolled back, and only for reserve: a busy cancel or an
   * overloaded GET is not a declined reservation.
   */
  private ResponseEntity<Map<String, String>> declined(
      HttpServletRequest req, HttpStatus status, String reason) {
    if (req.getRequestURI().endsWith("/reserve")) {
      meters.counter("reservations.declined", "reason", reason).increment();
    }
    return reason(status, reason);
  }

  private static boolean dbDown(SQLTransientConnectionException c) {
    if (c.getSQLState() != null && c.getSQLState().startsWith("08")) {
      return true;
    }
    for (Throwable t = c.getCause(); t != null; t = t.getCause()) {
      if (t instanceof ConnectException) {
        return true;
      }
    }
    return false;
  }

  private static ResponseEntity<Map<String, String>> unavailable(Exception e) {
    log.error("database unavailable", e);
    return reason(HttpStatus.SERVICE_UNAVAILABLE, "unavailable");
  }

  private static ResponseEntity<Map<String, String>> reason(HttpStatus status, String reason) {
    return ResponseEntity.status(status).body(Map.of("reason", reason));
  }
}
