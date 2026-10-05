# Seat Reservation Service

## Auth
HS256 JWT. `sub` is the user id; `role` claim `ADMIN` is required for `POST /shows`.
Identity comes only from the token; any `user_id` in a request body is ignored.
Secret: `JWT_SECRET` env var (>= 32 bytes, startup fails otherwise).

Dev token mint (enabled by `DEV_TOKEN_ENABLED=true`, on in `docker compose`, off by default):

```
curl -s -XPOST localhost:8080/auth/token -H 'Content-Type: application/json' \
  -d '{"sub":"u1","role":"ADMIN"}'
# {"token":"eyJ..."}   role optional, defaults to USER; token valid 1h
```

It accepts any `sub` (no users table), so it is full trust: never enable it on a real production deploy.
Public: `/actuator/health/**`, `/actuator/prometheus`. Everything else needs a bearer token.

## Reserve
`POST /shows/{id}/reserve` (any authenticated user, acts as the token's `sub`)

```
curl -s -XPOST localhost:8080/shows/1/reserve -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"seats":["A12","A13"],"idempotency_key":"7f9c..."}'
# 201 {"reservation_id":"…","show_id":1,"user_id":"u1","seats":["A12","A13"],"amount_paise":50000,"status":"confirmed"}
```

- **All-or-nothing.** Every requested seat is confirmed, or none is. If one seat is taken the whole
  request is declined and the free seats stay free. Duplicates are dropped; seats come back sorted.
- **Idempotency.** `idempotency_key` is scoped per user. The same key with the same show and seats
  (any order) returns the stored reservation with 201 and moves nothing; with a different show or
  seats it is 409. Declined requests are not stored, so retrying a declined key runs fresh. After a
  cancel the same key replays with `"status":"cancelled"`; booking again needs a new key.
- **Per-user limit.** A user holds at most `per_user_limit` seats per show (default 4), counted in seats.

| Status | `reason` | When |
|---|---|---|
| 201 | | Reserved, or a replay of the same key |
| 400 | | Empty or > 100 seats, bad seat label, missing key or key > 128 chars |
| 401 | | Missing or invalid token |
| 404 | | Unknown show or unknown seat (nothing is reserved) |
| 409 | `seat_taken` | A requested seat is confirmed, or being confirmed, by someone else |
| 409 | `per_user_limit` | The request would take the user past the show's limit |
| 409 | `idempotency_conflict` | Key already used for a different request |
| 409 | `busy` | Lock or statement timeout; safe to retry with the same key |
| 409 | `conflict` | Any other constraint violation |
| 429 | `overloaded` | Too many reserves in flight; retry with the same key |
| 503 | `unavailable` | Database unreachable |

## Cancel
`POST /reservations/{id}/cancel` (owner only, acts as the token's `sub`)

```
curl -s -XPOST localhost:8080/reservations/$RID/cancel -H "Authorization: Bearer $TOKEN"
# 204
```

Frees the reservation's seats and gives them back to the user's per-show limit, in one transaction.
The seats are immediately re-bookable. Release is explicit; holds do not expire (reserve confirms
immediately, see WRITEUP).

| Status | `reason` | When |
|---|---|---|
| 204 | | Cancelled, or already cancelled by its owner (repeat is a no-op) |
| 400 | | `id` is not a UUID |
| 401 | | Missing or invalid token |
| 404 | | Unknown id, or someone else's reservation (existence is not revealed) |
| 409 | `busy` | Lock or statement timeout; safe to retry |
| 429 | `overloaded` | Connection pool exhausted; retry |
| 503 | `unavailable` | Database unreachable |

Design and trade-offs: [WRITEUP.md](WRITEUP.md).

## Observe
Public, no token:

| Path | What |
|---|---|
| `/actuator/health/liveness` | Process only, never checks the DB (a DB outage must not restart the app). Point the platform check here. |
| `/actuator/health/readiness` | Includes `db`; DB unreachable → `DOWN`, 503 within the 2s pool timeout |
| `/actuator/prometheus` | Prometheus text format |

| Metric | Meaning |
|---|---|
| `reservations_confirmed_total` | New reservations committed (one per reservation, not per seat) |
| `reservations_declined_total{reason}` | Reserve declines: `seat_taken`, `per_user_limit`, `idempotent_replay`, `idempotency_conflict`, `busy`, `overloaded`. A replay returns 201 but counts here, never as confirmed |
| `seats{show_id,status}` | Seats per show and status, read from the DB every 5s (up to 5s stale) |
| `hikaricp_*`, `http_server_requests_seconds` | Pool and HTTP latency/status |

Reconcile after a burst (counters reset on restart, so compare deltas over the run; wait 5s for the
gauge): confirmed delta = fresh 201s; declined deltas = 409/429s by `reason` plus replayed 201s;
`seats{status="confirmed"}` = the `confirmed` count from `GET /shows/{id}`.
