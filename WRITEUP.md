# Writeup

## Reserve: where the atomic decision lives

One seat is one row in `seats` with a `status`. A reserve is a single transaction, in this order:

1. `SET LOCAL lock_timeout = '500ms'`, `statement_timeout = '2s'` (via `set_config(..., true)`).
2. **Idempotency.** `INSERT INTO reservations ... SELECT ... FROM shows ON CONFLICT (user_id, idempotency_key) DO NOTHING RETURNING amount_paise`.
   No row back means the key is taken (replay or 409) or the show is missing (404).
3. **Per-user limit.** One upsert on `user_show_quota`: `held = held + n WHERE held + n <= max_held`. No row back → 409 `per_user_limit`.
4. **Seats.** `SELECT status FROM seats WHERE show_id = ? AND seat_no = ANY(?) ORDER BY seat_no FOR UPDATE NOWAIT`.
   Fewer rows than requested → 404; any row not `AVAILABLE`, or a row locked by someone else (55P03) → 409 `seat_taken`.
5. `UPDATE seats SET status = 'CONFIRMED', reservation_id = ? WHERE ...`, then commit.

The decision "is this seat mine" is the row lock in step 4, not a read in Java. A plain read followed
by a write decided in Java would let two transactions both see `AVAILABLE` and both write; a row lock
cannot be held by two transactions, and the status is read under it. Every decline throws and rolls
back the whole transaction, so a loser leaves no reservation row and no quota increment behind.

The seat lock is deliberately the **last** step. By the time a transaction holds a hot seat, every
check that could fail has passed, so the lock holder commits; that is what makes "exactly one 201 per
hot seat" hold rather than "the holder rolled back and nobody got it".

JPA `findAll` + `saveAll` was rejected: it reads without a lock, decides in Java, and Hibernate's
`UPDATE ... WHERE id = ?` carries no status guard, so it double-sells under load.

## Partial requests: all-or-nothing

`["A12","A13"]` with only A12 free is declined with 409 and A12 stays free. Seats are sorted and
deduplicated in Java (`TreeSet`) before anything runs: `n`, the amount (`price_paise * n`, computed in
SQL) and the idempotency hash all use the deduplicated list, so `["A1","A1"]` is charged once. An
unknown seat is a 404, never a partial reservation.

This holds under concurrency because the whole request is one transaction: either step 5 runs for
every seat and commits, or something threw and nothing did. `overlappingMultiSeatRequestsAreAllOrNothing`
storms 300 users at overlapping pairs on a ring of 6 seats (both orders) and checks that every 201
owns both its seats, no seat is owned twice, and the database matches the 201s seat for seat.

## Deadlock avoidance

- **Seats never wait.** `NOWAIT` turns "locked by someone else" into an immediate decline. A
  transaction that never waits on a seat cannot be part of a wait-for cycle through seats, whatever
  order seats are requested in.
- **Fixed order across tables:** reservation row → quota row → seats, in reserve and cancel alike.
  On the reserve side the only waits left are on a same-user, same-key insert in flight (unique index)
  and on the same user's quota row. A reserve that holds the quota row only goes on to `NOWAIT` seat
  locks, so it never waits while holding it. A cancel can wait on its own seats (a reserve's `NOWAIT`
  probe holds them briefly), but that reserve never waits on anything the cancel holds, so no cycle
  can close.
- **Sorted anyway.** Seats are locked in `ORDER BY seat_no` order, which makes outcomes deterministic.
  `seat_no` is `TEXT COLLATE "C"` (byte order), and Java's `String` order (UTF-16 code units) is the
  same order for the allowed labels `[A-Za-z0-9-]`, so the Java sort and the SQL lock order agree.
- **Backstops.** `lock_timeout` bounds every wait at 500ms, and deadlock (40P01), lock timeout (55P03)
  and statement timeout (57014) all map to 409 `busy`, never 500.

## Idempotency

`UNIQUE (user_id, idempotency_key)` plus `request_hash`, the readable string `"showId:A1,A2"` (sorted,
deduplicated). Same key + same hash → 201 with the stored reservation: seats rebuilt from the hash
(they survive a cancel freeing them), status as it is now, so a replay after a cancel says
`cancelled` rather than claiming a booking that no longer exists. Same key + different hash → 409
`idempotency_conflict`. A second request with a key still in flight waits on the unique index: if the
first commits it replays, if the first rolls back its own insert goes through. Only successes are
stored; a declined key can be retried fresh. The replay returns before the quota and seat steps, so a
retry moves nothing.

## Release: explicit cancel, no expiring holds

The spec allows either an owner-only cancel or a time-boxed hold that expires. Reserve already
returns `confirmed`, so there is no hold to expire; explicit cancel needs no `HELD` state, no clock
and no sweeper job. A lazy expiry (treat an old `HELD` row as free at claim time, no sweeper) was
rejected: the expired holder's quota row is never decremented, so they stay locked out of their own
limit, and the `seats{status}` gauge counts dead holds as held. Expiry is the stretch ticket
(IMS-44); its sweeper would be this same transaction with a different `WHERE`.

One transaction, same lock order as reserve:

1. `UPDATE reservations SET status = 'CANCELLED' WHERE id = ? AND user_id = ? AND status = 'CONFIRMED' RETURNING ...`
   This is the decision. A concurrent cancel of the same id waits on the row lock, re-checks the
   `WHERE` after the first commits, and matches nothing.
2. `UPDATE user_show_quota SET held = held - n`, `n` from the stored `request_hash`.
3. `UPDATE seats SET status = 'AVAILABLE', reservation_id = NULL WHERE reservation_id = ?`.

**Never resurrecting someone else's seat.** Step 3 matches by owner id, never by seat label, so a seat
since confirmed to another reservation carries a different `reservation_id` and is not touched. A
repeat cancel stops at step 1 before it reaches the quota or the seats.

**No match in step 1** is answered by a read that only chooses the status code: the caller's own,
already-cancelled reservation → 204 (repeat is a no-op); unknown or someone else's → 404 for both,
so an id's existence is not revealed.

## Errors: zero 5xx on domain outcomes

Declines are `ConflictException(reason)` → 409. Database errors are classified by SQLState anywhere
in the cause chain (Spring's translated exception type for these varies by version):
55P03 / 57014 / 40P01 → 409 `busy`; a constraint violation → 409 `conflict`. As a backstop, a
`PessimisticLockingFailureException` (which covers `CannotAcquireLockException`) or a
`QueryTimeoutException` that arrives without the driver's `SQLException` is also 409 `busy`.

A deadlock (40P01) should be impossible with the fixed lock order, so it is still 409 but logged at
WARN: a 409 must not hide a real deadlock. It is detected by SQLState, because Spring has not thrown
`DeadlockLoserDataAccessException` since 6.0.3 (deprecated; a deadlock arrives as a plain
`PessimisticLockingFailureException`).

Anything else from the database is 500 and logged at ERROR, so a genuine bug (a missing table, bad
SQL) stays visible. `ApiExceptionHandlerTest` runs the whole table, including that 500 row.
Validation (400) and auth (401/403) are handled before this advice and have their own tests.

Pool exhaustion arrives as `CannotCreateTransactionException` wrapping Hikari's
`SQLTransientConnectionException`. If that exception (or its cause) says the database is unreachable
(SQLState `08xxx` or a `ConnectException`) the response is 503; otherwise every connection is just
busy and it is 429 `overloaded`.

## Admission: 429 vs 409

A fair `Semaphore` of `max(1, pool - 2)` permits sits in front of the transaction, so reserves never
queue on the pool and two connections stay free for `GET /shows` and the readiness check. A request
waits up to `app.reserve.admission-wait` (default 15s, env `APP_RESERVE_ADMISSIONWAIT`) for a
permit, then gets 429. The permit is released only if it was acquired. The semaphore is fair and is
acquired with the timed `tryAcquire`, which honours fairness, so late arrivals cannot barge past the
queue.

**429 is an overload valve, not an expected outcome.** The bar is exactly one 201 per hot seat and 409
for everyone else. A 429 on a hot-seat loser breaks that literally, and a 429 on the would-be winner
leaves the seat with no 201 at all. So the wait is sized for nothing to time out at the expected load:

    max admission wait ≈ N / tx/s      (N = the client's in-flight cap)

A client never has more than N requests open, so the queue at the semaphore is at most N deep,
however large the burst is. The time to drain all 20k does not set anyone's wait. A 15s ceiling
covers N up to 15 × tx/s. It is a ceiling, not added latency: a request waits only as long as the
queue ahead of it, and a waiting request holds no database connection.

| Run | In-flight N | Purpose |
|---|---|---|
| Expected load | 1000 | documented sizing input, plausible grader setting |
| Stress | 2000 | shows the ceiling has a 2× margin, not just a pass |
| Hot-seat storm | 500 | correctness (one 201, the rest 409), not capacity |

*Measured locally: tx/s, 429 count per run — pending the burst run.* Docker Postgres on the same
machine is much faster than the deployed Neon/Fly database, so the local tx/s overstates deployed
throughput. IMS-42 reruns the same burst against the deployed tier and re-checks 15s against that
tx/s. If 429s still appear, raise the wait. An in-process "already confirmed" cache is ruled out (no
caches on the reserve path, single-instance only, and cancel would have to evict).

## Known gaps

- **NOWAIT rollback gap.** A loser is declined the instant the seat is locked. If the lock holder then
  fails to commit (connection loss, crash), the seat is free again but that loser already got 409.
  Rare, since only the `UPDATE` and the commit follow the lock, and the next request takes the seat.
- **Stale Hikari failure.** Hikari 7.0.2 clears its last connection failure when a new connection is
  created, but not after a failed validation. If a validation failure with an `08` state is followed by
  pool exhaustion before any refill, that exhaustion is reported as 503 instead of 429.
- **`max_held` is copied** from `per_user_limit` at a user's first claim for a show; a later change to
  the limit does not reach users who already hold seats (no endpoint changes it today).
