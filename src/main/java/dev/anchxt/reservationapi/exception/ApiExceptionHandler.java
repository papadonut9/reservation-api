package dev.anchxt.reservationapi.exception;

import java.net.ConnectException;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
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

  @ExceptionHandler(ConflictException.class)
  ResponseEntity<Map<String, String>> conflict(ConflictException e) {
    return reason(HttpStatus.CONFLICT, e.getMessage());
  }

  @ExceptionHandler(OverloadedException.class)
  ResponseEntity<Map<String, String>> overloaded(OverloadedException e) {
    return reason(HttpStatus.TOO_MANY_REQUESTS, e.getMessage());
  }

  @ExceptionHandler({DataAccessException.class, TransactionException.class})
  public ResponseEntity<Map<String, String>> database(RuntimeException e) {
    for (Throwable t = e; t != null; t = t.getCause()) {
      // Hikari pool timeout: carries the last connection failure (and its 08xxx state) when the
      // DB is down; no such failure means every connection is simply busy
      if (t instanceof SQLTransientConnectionException c) {
        return dbDown(c) ? unavailable(e) : reason(HttpStatus.TOO_MANY_REQUESTS, "overloaded");
      }
      if (t instanceof SQLException s && s.getSQLState() != null) {
        if (BUSY.contains(s.getSQLState())) {
          return reason(HttpStatus.CONFLICT, "busy");
        }
        if (s.getSQLState().startsWith("08")) {
          return unavailable(e);
        }
      }
    }
    if (e instanceof DataIntegrityViolationException) {
      return reason(HttpStatus.CONFLICT, "conflict");
    }
    log.error("unmapped database error", e);
    return reason(HttpStatus.INTERNAL_SERVER_ERROR, "internal");
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
