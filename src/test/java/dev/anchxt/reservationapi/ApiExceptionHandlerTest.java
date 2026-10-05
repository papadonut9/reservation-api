package dev.anchxt.reservationapi;

import static org.assertj.core.api.SoftAssertions.assertSoftly;

import dev.anchxt.reservationapi.exception.ApiExceptionHandler;
import java.net.ConnectException;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.transaction.CannotCreateTransactionException;

/**
 * Every database exception a domain outcome can raise maps to a 4xx; only a dead database (503) or
 * a genuine bug (500) is 5xx. Validation (400) and auth (401/403) never reach this advice; see
 * invalidRequestIs400, noTokenIs401, postShowsIsAdminOnly.
 */
class ApiExceptionHandlerTest {

  static UncategorizedSQLException state(String sqlState) {
    return new UncategorizedSQLException("reserve", "sql", new SQLException("x", sqlState));
  }

  @Test
  void domainOutcomesAreNever5xx() {
    var advice = new ApiExceptionHandler();
    var cases =
        List.<Map.Entry<RuntimeException, String>>of(
            Map.entry(new DataIntegrityViolationException("x"), "409 conflict"),
            Map.entry(new PessimisticLockingFailureException("x"), "409 busy"),
            Map.entry(new CannotAcquireLockException("x"), "409 busy"),
            Map.entry(new QueryTimeoutException("x"), "409 busy"),
            Map.entry(state("55P03"), "409 busy"), // lock_timeout, NOWAIT
            Map.entry(state("57014"), "409 busy"), // statement_timeout
            Map.entry(state("40P01"), "409 busy"), // deadlock, logged at WARN
            Map.entry(
                new CannotCreateTransactionException(
                    "x", new SQLTransientConnectionException("pool timeout")),
                "429 overloaded"),
            Map.entry(
                new CannotCreateTransactionException(
                    "x", new SQLTransientConnectionException("x", new ConnectException())),
                "503 unavailable"),
            // a real bug (missing table) must stay visible
            Map.entry(
                new BadSqlGrammarException("reserve", "sql", new SQLException("x", "42P01")),
                "500 internal"));
    assertSoftly(
        soft -> {
          for (var c : cases) {
            var r = advice.database(c.getKey());
            soft.assertThat(r.getStatusCode().value() + " " + r.getBody().get("reason"))
                .as(c.getKey().getClass().getSimpleName())
                .isEqualTo(c.getValue());
          }
        });
  }
}
