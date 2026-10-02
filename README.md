# Seat Reservation at Scale

A JSON HTTP API that sells assigned seats and stays correct under an on-sale stampede:
no seat sold twice, no user over their limit, no retry charged twice, zero 5xx on
domain declines.

**Stack:** Java 21 · Spring Boot 4.1.1 · PostgreSQL 18 · Flyway · Docker
**Live URL:** _TBD_
**Write-up:** [`docs/WRITEUP.md`](docs/WRITEUP.md) · **Design notes:** [`docs/STRUCTURE.md`](docs/STRUCTURE.md)

Measured locally, 20,000 concurrent reservations against a fresh 1000-seat show:

```
confirmed                    998
409 seat_taken            18,913
409 per_user_limit            86
409 idempotency_conflict       3
5xx                            0
reconciliation   2 + 998 + 0 = 1000 / 1000   PASS
every hot seat claimed exactly once          PASS
```

---

## Run it

```bash
cp .env.example .env          # fill POSTGRES_PASSWORD, DB_PASSWORD, JWT_SECRET,
                              # ADMIN_USERNAME, ADMIN_PASSWORD
docker compose up --build -d  # postgres + redis + app
curl localhost:8080/healthz
```

`JWT_SECRET` must be at least 32 bytes — the app refuses to boot otherwise, by design.

Flyway migrates on startup; `ddl-auto=validate` means a schema/entity mismatch fails the
boot rather than surfacing at the first query.

### Without Docker

```bash
docker compose up -d db       # just Postgres
./mvnw spring-boot:run        # app reads .env directly
```

---

## One-command burst

```bash
./scripts/burst.sh <BASE_URL> [requests] [users] [inFlight]

./scripts/burst.sh http://localhost:8080                  # 20000 / 2000 / 1500
./scripts/burst.sh https://<live-url> 5000 500 300        # gentler, for a free tier
```

Single-file Java on virtual threads — no extra tooling to install. It creates a fresh
1000-seat show, registers N users, then releases every request simultaneously through a
`CountDownLatch` so they genuinely collide. 70% of traffic targets two hot seats, 10%
replays an earlier idempotency key, and every body carries a spoofed `user_id` to prove
it is ignored.

Prints the outcome distribution, throughput, p50/p95/p99, then re-reads `GET /shows/{id}`
and asserts reconciliation. **Exits non-zero** on any 5xx, any transport failure, a
reconciliation mismatch, or a hot seat claimed more than once.

`ADMIN_USERNAME` / `ADMIN_PASSWORD` are read from `.env`, or from the environment when
running against the deploy.

---

## API

| Method | Path | Auth | Notes |
|---|---|---|---|
| POST | `/auth/register` | — | always creates `USER`; returns a token |
| POST | `/auth/login` | — | returns a token |
| POST | `/shows` | admin | creates the show and all seats |
| GET | `/shows/{id}` | — | per-seat status and counts |
| POST | `/shows/{id}/reserve` | user | seats + idempotency key → 201 `held` |
| POST | `/reservations/{id}/confirm` | owner | `held` → `confirmed` |
| POST | `/reservations/{id}/cancel` | owner | releases the hold |
| GET | `/healthz/liveness` | — | cheap, no dependencies |
| GET | `/healthz/readiness` | — | pings Postgres, **503 when unreachable** |
| GET | `/metrics` | — | Prometheus |

Wire format is snake_case (`price_paise`, `idempotency_key`, `reservation_id`). Money is
integer paise throughout.

```bash
# create a show
curl -XPOST $BASE/shows -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' \
  -d '{"name":"friday-night","seats":["A1","A2","A12"],"price_paise":25000,"per_user_limit":4}'

# reserve
curl -XPOST $BASE/shows/$SHOW/reserve -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -H "Idempotency-Key: $(uuidgen)" \
  -d '{"seats":["A12"]}'
```

A Postman collection with 26 requests — including every decline path and assertions for
the reconciliation invariant — is in [`postman/`](postman/).

### Declines

Every domain outcome is a 4xx carrying a machine-readable `reason`, shared verbatim by
the API, the metric labels and the logs.

| `reason` | HTTP |
|---|---|
| `seat_taken` | 409 |
| `per_user_limit` | 409 |
| `idempotency_conflict` | 409 |
| `idempotent_in_flight` | 409 |
| `hold_expired` | 409 |
| `show_already_exists` · `user_already_exists` | 409 |
| `not_owner` | 403 |
| `show_not_found` · `seat_not_found` · `reservation_not_found` | 404 |
| `validation_failed` | 400 |
| `unauthenticated` | 401 |
| `rejected_overload` | 429 |

---

## Behaviour that holds

- **No double-sell.** One guarded `UPDATE` decides; the affected row count is the verdict.
- **Multi-seat is all-or-nothing**, with rows locked in `id` order so concurrent requests
  for overlapping seat sets cannot deadlock.
- **Idempotency** is enforced by the primary key on `(user_id, idempotency_key)`, not by
  an application check. Same key → same reservation; same key, different seats → 409.
- **Holds expire lazily** — a lapsed hold is claimable immediately, so
  `available + held + confirmed == total_seats` is true *continuously*, not after a sweep.
- **Identity is token-derived.** `ReserveRequest` has no `user_id` field at all; a spoofed
  one is discarded by Jackson before any code sees it.

Mechanisms and trade-offs: [`docs/WRITEUP.md`](docs/WRITEUP.md).

---

## Observability

```bash
curl $BASE/metrics | grep -E 'reservations_|seats_'
curl -D- $BASE/healthz -o /dev/null | grep -i x-request-id
docker compose logs -f app          # ECS JSON, one request id per line
```

`X-Request-Id` is honoured from the caller when supplied and generated otherwise, echoed
on the response and present in every log line for that request.

---

## Layout

```
src/main/java/com/social/seat_reservation/
├── api/          controllers, DTOs, error mapping
├── domain/       entities, enums, typed decline exceptions
├── repository/   Spring Data + the atomic claim query
├── service/      transaction boundaries and decisions
├── security/     JWT parsing, token-derived identity
├── config/       security, clock, properties, admin bootstrap
└── observability/ request id filter, metrics, gauges
src/main/resources/db/migration/   Flyway V1, V2
scripts/          burst harness
postman/          API collection with assertions
docs/             WRITEUP, STRUCTURE, TASKS
```
