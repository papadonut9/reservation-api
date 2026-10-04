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
- **Fixed order across tables:** reservation key → quota row → seats, everywhere (cancel, IMS-36, must take them in the same order).
  The only waits left are on a same-user, same-key insert in flight (unique index) and on the same
  user's quota row. A transaction that holds the quota row only goes on to `NOWAIT` seat locks, so it
  never waits while holding it, and no cycle can close.
- **Sorted anyway.** Seats are locked in `ORDER BY seat_no` order, which makes outcomes deterministic.
  `seat_no` is `TEXT COLLATE "C"` (byte order), and Java's `String` order (UTF-16 code units) is the
  same order for the allowed labels `[A-Za-z0-9-]`, so the Java sort and the SQL lock order agree.
- **Backstops.** `lock_timeout` bounds every wait at 500ms, and deadlock (40P01), lock timeout (55P03)
  and statement timeout (57014) all map to 409 `busy`, never 500.

## Idempotency

`UNIQUE (user_id, idempotency_key)` plus `request_hash`, the readable string `"showId:A1,A2"` (sorted,
deduplicated). Same key + same hash → the original 201 body, rebuilt from the stored row and hash
(so it survives a later cancel freeing the seats). Same key + different hash → 409
`idempotency_conflict`. A second request with a key still in flight waits on the unique index: if the
first commits it replays, if the first rolls back its own insert goes through. Only successes are
stored; a declined key can be retried fresh. The replay returns before the quota and seat steps, so a
retry moves nothing.

## Errors: zero 5xx on domain outcomes

Declines are `ConflictException(reason)` → 409. Database errors are classified by SQLState anywhere
in the cause chain (Spring's translated exception type for these varies by version):
55P03 / 57014 / 40P01 → 409 `busy`; a constraint violation → 409 `conflict`.

Pool exhaustion arrives as `CannotCreateTransactionException` wrapping Hikari's
`SQLTransientConnectionException`. If that exception (or its cause) says the database is unreachable
(SQLState `08xxx` or a `ConnectException`) the response is 503; otherwise every connection is just
busy and it is 429 `overloaded`.

## Admission: 429 vs 409

A fair `Semaphore` of `max(1, pool - 2)` permits sits in front of the transaction, so reserves never
queue on the pool and two connections stay free for `GET /shows` and the readiness check. A request
waits up to `app.reserve.admission-wait` (default 2s) for a permit, then gets 429. The permit is
released only if it was acquired.

The trade-off: the correctness bar wants "everyone else 409" on a hot seat, but a 20k burst against
13 permits will turn part of the losers into 429 before they reach the seat. 429 is still a 4xx and
still a decline; nothing is double-sold. The wait is a config knob so the burst run can tune it.
*Measured 429/409 split: to be filled in from the burst run.* If 429s dominate, the next lever is a
lock-free pre-check (a plain read that already sees `CONFIRMED` declines with 409 without a
transaction); a stale read there can only decline, never sell.

## Known gaps

- **NOWAIT rollback gap.** A loser is declined the instant the seat is locked. If the lock holder then
  fails to commit (connection loss, crash), the seat is free again but that loser already got 409.
  Rare, since only the `UPDATE` and the commit follow the lock, and the next request takes the seat.
- **Stale Hikari failure.** Hikari 7.0.2 clears its last connection failure when a new connection is
  created, but not after a failed validation. If a validation failure with an `08` state is followed by
  pool exhaustion before any refill, that exhaustion is reported as 503 instead of 429.
- **`max_held` is copied** from `per_user_limit` at a user's first claim for a show; a later change to
  the limit does not reach users who already hold seats (no endpoint changes it today).
