# Seat Reservation Service

Jira: IMS board, epic IMS-27. Prefix commits with the task key (e.g. `IMS-32: atomic seat claim`). Commit incrementally; history is graded.
## GROUND RULES

### Needs explicit human approval
- push, force-push, merge, tag, deploy
- any non-local DB (Neon, Fly, shared/prod), incl. running migrations against it
- changes to Fly/Neon config, Jira writes
- Allowed freely: local commits on the current feature branch, local Docker Postgres, Testcontainers, Jira reads
- Never: rewrite published history, `--no-verify`, commit secrets (env vars only, `.env` gitignored), destructive ops (DROP/TRUNCATE outside test DBs, rm -rf outside repo)

### Subagents
- Only for search, boilerplate, test scaffolding. Model: haiku, effort: low.
- Never delegate locking, SQL, idempotency or quota logic. Main agent reviews all output.

### Git flow
- master: bootstrap commit only (CLAUDE.md, README, .gitignore). pre-prod: cut from master.
- Feature branches are cut from pre-prod (pull latest first): `IMS-32-atomic-seat-claim`. Never from another feature branch.
- Linear history, no merge commits. Flow: feature → pre-prod (rebase on latest pre-prod, then GitHub "Rebase and merge"; never squash, per-ticket commits stay) → master (batch promote, fast-forward only: `git push origin pre-prod:master`). GitHub rebase-merge rewrites SHAs, so never use it for pre-prod → master.
- Dependent ticket starts only after its predecessor is merged to pre-prod.
  Order: IMS-28→29→30→31→32→33→34→35→36→37→38→39→40→41→42→43.
  IMS-44 (stretch) only after core is deployed.
- `mvn verify` green before merge to pre-prod.
- Deploy from master only. Promote pre-prod → master before IMS-42 (deploy), with approval.

### Package layout
- Package by layer under `dev.anchxt.reservationapi`: `controller` (HTTP only), `dto` (request/response records), `service` (transactions, orchestration), `repository` (all SQL), `model` (domain types/enums), `exception` (domain exceptions + advice), `config`.
- No SQL outside `repository`. No HTTP types (ResponseStatusException, Jwt) below `controller`.

### Disclosure
- Append to `AI_USAGE.md` per ticket: what was directed vs decided.

## 1. Goal
Sell assigned seats; never double-sell; survive an on-sale stampede; be observable.
- Burst target: ~20k concurrent reservations at one show, many on a few hot seats. Zero 5xx. Latency is secondary to correctness.
- Not a sustained 20k rps target. Do not add caches/queues to chase throughput.
- System of record: PostgreSQL only. No Redis, no message queue on the reserve path.

## 2. Stack
- Java 21 (virtual threads on), Spring Boot 3.x, Maven, Spring Data JPA/JDBC, Flyway, Postgres.
- Tests: JUnit 5 + Testcontainers (real Postgres, concurrent ExecutorService tests).
- Runtime: ~512MB container. `-XX:MaxRAMPercentage=75`, Hikari pool 10-15, `connection-timeout` 2s.

## 3. Concurrency rules (the correctness bar)
- NEVER optimistic locking (`@Version`).
- NEVER read-then-write: no plain SELECT followed by a write decided in Java.
- Claim a seat with an atomic conditional write:
  `UPDATE seats SET status='CONFIRMED', reservation_id=:rid WHERE show_id=:s AND seat_no=:n AND status='AVAILABLE'`
  1 row = won. 0 rows = taken → 409.
- Alternative allowed: `SELECT ... FOR UPDATE NOWAIT` (JPA `@Lock(PESSIMISTIC_WRITE)` + lock timeout 0) in sorted order, then update. A locking read is an atomic lock, not a race. Verify SQL log shows `for update nowait`.
- Multi-seat: all-or-nothing. Sort + dedupe seat ids, one transaction, process in that order, any failure → rollback → 409. No partial success. Document in README/WRITEUP.
- Hot-seat losers wait on the winner's row lock. Keep transactions tiny (no HTTP, no logging I/O inside). `SET LOCAL lock_timeout` ~500ms and `statement_timeout`; map both to 409.
- Bounded admission (Semaphore) before the transaction. Overflow → fast 429. Pool exhaustion must never surface as 500.
- Lock order across tables is fixed everywhere (reserve AND cancel): idempotency → quota → seats.
- Reserve returns CONFIRMED immediately (spec 201 body). HELD/expiry is the stretch task only (IMS-44).

## 4. Money
- Integer paise: `long` in Java, `BIGINT` in Postgres. No float/double/BigDecimal/NUMERIC.
- Multiply before dividing if division is ever needed.

## 5. Idempotency, limit, identity
- Idempotency: `reservations` has `UNIQUE(user_id, idempotency_key)` + `request_hash` (show + sorted seats). Insert first in the tx.
    - same key + same hash → replay original 201 body
    - same key + different hash → 409
    - failures roll back (only successes are stored). Document.
- Per-user limit (default 4): atomic counter row, NOT a count-then-check (that races under 10 parallel requests):
  `INSERT INTO user_show_quota ... ON CONFLICT (user_id, show_id) DO UPDATE SET held = held + :n WHERE held + :n <= :limit RETURNING held`
  No row returned → 409 per_user_limit. Same tx as seat claim; cancel decrements.
- Identity comes only from the JWT `sub`. Ignore any `user_id` in the body. Cancel only by owner (non-owner → 404).

## 6. Errors: zero 5xx on domain outcomes
- `@RestControllerAdvice`: DataIntegrityViolation, PessimisticLockingFailure, CannotAcquireLock, SQLState 55P03, lock/statement timeout → 409. Validation → 400. Auth → 401/403.
- 5xx only for true system failure (DB down). Write a test that no domain path returns 500.

## 7. Invariant
- One row per seat with a status enum. `available + held + confirmed == total_seats` always. No separate counter columns that can drift.
- Cancel frees only `WHERE reservation_id=:rid`, never a seat since re-confirmed to someone else.

## 8. Observability
- Actuator liveness (no DB) + readiness (includes `db`, fails closed 503). Platform check points at liveness.
- Counters: `reservations_confirmed_total`, `reservations_declined_total{reason=seat_taken|per_user_limit|idempotent_replay|idempotency_conflict}`.
- Gauge `seats{show_id,status}` read from the DB on a schedule, not in-memory increments.
- Increment counters after commit / in the exception advice, never inside the transaction.
- Tags: never user_id, seat_no, request id.
- Structured JSON logs with MDC `requestId` (accept `X-Request-Id` or generate; echo in response).
- Hikari + http_server_requests metrics on.

## 9. Delivery
- `docker compose up` works from a clean clone. Multi-stage Dockerfile.
- `make burst` / `./burst.sh <BASE_URL>`: hot-seat storm, ~20k stampede, same-key retries, 10-parallel user at limit 4, spoofed body user_id, final invariant check. Prints outcome table + pass/fail.
- WRITEUP.md: atomic mechanism, deadlock avoidance, idempotency, CP under partition, 2am alerts, honest AI usage, next steps.
- JSON API only. No UI. 