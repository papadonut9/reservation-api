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

### Logs
One JSON object per line (ECS) on stdout. Every line carries `requestId`: the inbound `X-Request-Id`
if it matches `[A-Za-z0-9-]{8,64}`, otherwise a generated UUID. The id is echoed in the
`X-Request-Id` response header, including on 401/403. One access line per request
(`POST /shows/1/reserve 409 3ms`); decline reasons are in the counters, not the logs.

```
curl -si -XPOST localhost:8080/shows/1/reserve -H 'X-Request-Id: burst-0001' ... | grep X-Request-Id
docker compose logs app | grep '"requestId":"burst-0001"'
```

## Burst
One command reproduces the on-sale stampede against any deployment and checks the correctness bar:

```
./burst.sh https://<live-url>                       # same as: java scripts/Burst.java <BASE_URL>
CONCURRENCY=5000 ./burst.sh http://localhost:8080   # cap in-flight requests (see below)
```

Needs JDK 21 (single-file source launch, no build, no dependencies). The target must run with
`DEV_TOKEN_ENABLED=true`: the script mints an admin token to create a fresh show and one user token
per simulated buyer. Env: `REQUESTS` (default 20000), `CONCURRENCY` (default = `REQUESTS`, all at once).

What one run does:
1. Waits up to 3 min for `/actuator/health/readiness` (free tiers cold-start).
2. Creates a fresh show: rows A-T × 50 for the burst, plus rows X/Y/Z reserved for the checks below.
3. Fires all requests behind one start gate, shuffled together:
   - hot-seat storm: 500 users each on A10..A14 (A12 included);
   - front-weighted traffic: 1-4 adjacent seats per request, distinct users;
   - same key ×20 in parallel (one user, Y1+Y2);
   - one user firing 10 parallel single-seat reserves at limit 4 (Z1..Z10).
4. Polls `GET /shows/{id}` during the burst and asserts `available + held + confirmed == total_seats`.
5. After the burst: same key with different seats, a spoofed body `user_id`, and a cancel of someone
   else's reservation.
6. Waits for the 5s gauge refresh, then reconciles `GET /shows/{id}` and `/actuator/prometheus`
   (deltas over the run) against what the client saw.

It prints an outcome table per group (201 / replay / 409 by reason / 429 / other 4xx / 5xx /
transport) and PASS/FAIL per bar, and exits 1 on any FAIL. Every request carries
`X-Request-Id: burst-<run>-<n>`, so `grep burst-<run>` finds the run in the logs.

- A request that gets no answer (connection dropped in front of the app) is retried up to 3 times
  with the same idempotency key, as a real client would; the run line reports how many.
- The Prometheus checks assume one instance and no other traffic during the run.
- One machine cannot hold 20k plain-HTTP connections (Windows runs out of sockets, Tomcat accepts
  8192), so cap `CONCURRENCY` for local runs. A dropped connection shows as `transport`, never as a
  server outcome.
