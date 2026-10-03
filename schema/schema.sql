-- DRAFT (IMS-29). Plain SQL, not a Flyway migration. Postgres 16.
-- Money: BIGINT paise only. Lock order everywhere: idempotency -> quota -> seats.
-- Status values are CHECKs, not lookup tables: an FK to a status row would
-- KEY SHARE-lock that one row on every seat claim (hot row under burst).

CREATE TABLE shows (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name           TEXT   NOT NULL,
    price_paise    BIGINT NOT NULL CHECK (price_paise >= 0),
    per_user_limit INT    NOT NULL DEFAULT 4 CHECK (per_user_limit > 0),
    total_seats    INT    NOT NULL CHECK (total_seats > 0)   -- immutable; invariant target
);

CREATE TABLE reservations (
    id              UUID        PRIMARY KEY,
    show_id         BIGINT      NOT NULL REFERENCES shows (id),
    user_id         TEXT        NOT NULL CHECK (length(user_id) <= 128),          -- JWT sub
    idempotency_key TEXT        NOT NULL CHECK (length(idempotency_key) <= 128),
    request_hash    TEXT        NOT NULL,                  -- hash(show + sorted seats)
    amount_paise    BIGINT      NOT NULL CHECK (amount_paise >= 0),
    status          TEXT        NOT NULL
                            CONSTRAINT ck_reservations_status CHECK (status IN ('CONFIRMED', 'CANCELLED')),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_reservations_user_key UNIQUE (user_id, idempotency_key)
);

-- Seeded 1..total_seats per show. Cancel updates in place: status -> AVAILABLE,
-- reservation_id -> NULL. Seat history is not kept (audit out of scope).
CREATE TABLE seats (
    show_id        BIGINT NOT NULL REFERENCES shows (id),
    seat_no        INT    NOT NULL CHECK (seat_no > 0),     -- INT: deterministic lock order
    status         TEXT   NOT NULL DEFAULT 'AVAILABLE'
                          CONSTRAINT ck_seats_status CHECK (status IN ('AVAILABLE', 'HELD', 'CONFIRMED')),
    reservation_id UUID   REFERENCES reservations (id),
    PRIMARY KEY (show_id, seat_no),
    -- structural: AVAILABLE <=> no owner
    CONSTRAINT ck_seats_owner CHECK ((status = 'AVAILABLE') = (reservation_id IS NULL))
);
-- cancel: UPDATE seats ... WHERE reservation_id = :rid
CREATE INDEX seats_reservation_idx ON seats (reservation_id) WHERE reservation_id IS NOT NULL;

CREATE TABLE user_show_quota (
    user_id TEXT   NOT NULL CHECK (length(user_id) <= 128),
    show_id BIGINT NOT NULL REFERENCES shows (id),
    held    INT    NOT NULL,
    max_held INT   NOT NULL,                  -- copied from shows.per_user_limit at first insert
    PRIMARY KEY (user_id, show_id),
    CONSTRAINT ck_quota_bounds CHECK (held >= 0 AND held <= max_held)  -- backstop: first insert can't bypass
);
-- claim (guards both insert and update path; no row returned => 409 per_user_limit):
-- INSERT INTO user_show_quota (user_id, show_id, held, max_held)
-- SELECT :u, s.id, :n, s.per_user_limit FROM shows s WHERE s.id = :s AND :n <= s.per_user_limit
-- ON CONFLICT (user_id, show_id) DO UPDATE SET held = user_show_quota.held + :n
--   WHERE user_show_quota.held + :n <= user_show_quota.max_held
-- RETURNING held;
