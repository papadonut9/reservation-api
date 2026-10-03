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
