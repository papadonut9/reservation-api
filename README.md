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
  (any order) returns the original 201 and moves nothing; with a different show or seats it is 409.
  Declined requests are not stored, so retrying a declined key runs fresh.
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

Design and trade-offs: [WRITEUP.md](WRITEUP.md).
