# ER diagram (IMS-29)

```mermaid
erDiagram
    shows ||--o{ seats : "has (seeded from POST /shows labels)"
    shows ||--o{ reservations : "for"
    shows ||--o{ user_show_quota : "limits"
    reservations |o--o{ seats : "owns via reservation_id"

    shows {
        bigint id PK
        text name
        bigint price_paise "CHECK >= 0"
        int per_user_limit "default 4, CHECK > 0"
        int total_seats "immutable, invariant target"
    }
    seats {
        bigint show_id PK,FK
        text seat_no PK "label e.g. A1, COLLATE C, len 1..99"
        text status "ck_seats_status AVAILABLE|HELD|CONFIRMED"
        uuid reservation_id FK "NULL iff AVAILABLE"
    }
    reservations {
        uuid id PK
        bigint show_id FK
        text user_id "JWT sub, len <= 128"
        text idempotency_key "uq(user_id, idempotency_key), len <= 128"
        text request_hash "hash(show + sorted seats)"
        bigint amount_paise "CHECK >= 0"
        text status "ck_reservations_status CONFIRMED|CANCELLED"
        timestamptz created_at
    }
    user_show_quota {
        text user_id PK "JWT sub, len <= 128"
        bigint show_id PK,FK
        int held "ck_quota_bounds 0..max_held"
        int max_held "copied from per_user_limit"
    }
```

Lock order (reserve and cancel): `reservations` (idempotency) → `user_show_quota` → `seats`.
`user_show_quota.user_id` and `reservations.user_id` are plain JWT subs, with no users table.
Cancel updates seats in place (status → AVAILABLE, reservation_id → NULL); seat history is not kept.
A same-key replay after cancel cannot return the original seat list; behaviour to be documented in README.
