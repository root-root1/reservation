# Seat Reservation at Scale

A JSON HTTP API that sells assigned seats for a show and stays correct under a ~20k-concurrent
on-sale stampede: no seat sold twice, no user over their limit, no retry charged twice, zero 5xx
on domain declines.

**Stack:** Java 21 · Spring Boot 4.1.1 · PostgreSQL · Flyway · Docker
**Live URL:** _(pending — Step 7)_

> Status: **planning / scaffolding.** Project skeleton, dependencies and package layout are in
> place. No business logic written yet. Build is verified: `./mvnw package` produces a jar.

Design rules, testing strategy and the commit checklist live in **[`docs/STRUCTURE.md`](docs/STRUCTURE.md)**.

---

## The problem in one paragraph

500 people hit seat A12 in the same millisecond. Exactly one gets a 201. The other 499 get a
clean 409 — not a 500, not a lock-wait timeout, and definitely not a second copy of A12. A
read-then-write (`is A12 free? ok, take it`) fails this. The decision has to be a single atomic
step in the datastore. Everything else in this repo is in service of that sentence.

---

## Invariants

These are the statements a checker could assert at any instant. They are the spec.

1. A seat has at most one active claim — a seat confirmed or actively held for one user is never
   confirmed or held for another.
2. `available + held + confirmed == total_seats`, per show, continuously — during the burst, not
   just after it.
3. A user holds at most `per_user_limit` seats per show (default 4).
4. One idempotency key yields at most one reservation, forever.
5. Acting identity always equals the token's subject. A `user_id` in a request body is ignored.
6. Every domain outcome is 4xx. A 5xx means the service is broken, not that the caller was
   refused.

_Step 1 is not finished: the exact wording of #2 depends on how an expired-but-unreaped hold is
counted. That decision shapes the schema, so it lands before any migration._

## Error contract

Declines are a closed vocabulary, shared verbatim by the API, the metric labels and the logs —
so a 409 in a log line and a counter increment are provably the same event.

| `reason` | HTTP | Meaning |
|---|---|---|
| `seat_taken` | 409 | At least one requested seat is held or confirmed |
| `per_user_limit` | 409 | Request would exceed the user's seat limit for the show |
| `idempotency_conflict` | 409 | Key reused with a different body |
| `not_owner` | 403 | Cancel attempted on someone else's reservation |
| `show_not_found` / `seat_not_found` | 404 | Unknown show or seat label |
| `validation_failed` | 400 | Malformed request |
| `unauthenticated` | 401 | Missing or unverifiable token |

An idempotent replay is **not** a decline — it returns the original reservation. It still gets a
counter, because "how many retries did we absorb" is a thing worth watching.

---

## Build order

Nine steps, one at a time, each ending in commits. Open decisions are listed per step and must be
answered before that step closes.

### 1 · Requirements & invariants
Invariants and error contract above, finalised. Decide how an expired-but-unreaped hold is
counted in the reconciliation identity.

### 2 · Data model & the atomic decision — *the core of the grade*
Tables: `shows`, `seats`, `reservations`, `reservation_seats`, `idempotency_keys`. Seats unique
on `(show_id, seat_label)`. Flyway migrations; `ddl-auto=validate`.

Decide and defend:
- **The mechanism**, named precisely — guarded `UPDATE … WHERE status = 'available'` checking the
  affected-row count, or a unique-constraint insert that makes a second claim physically
  impossible. Why the other was rejected.
- **Multi-seat semantics:** all-or-nothing vs best-effort. Documented, and holding under
  concurrency.
- **Deadlock avoidance:** rows acquired in deterministic order, so `[A12,A13]` and `[A13,A12]`
  can't deadlock.
- **Isolation level**, and why it's sufficient.
- **Pool sizing** against the free-tier connection cap — on a free tier the pool, not Postgres,
  is usually the real ceiling.
- What the 499 losers experience: a fast clean decline, not a lock wait.

### 3 · Idempotency
Key stored in its own table, unique on `(user_id, idempotency_key)` — uniqueness enforced by the
database, never by an application-level check.

Decide: insert-key-first vs claim-first, and what a crash between the two leaves behind; whether
a replay returns 200 or 201; how the body fingerprint is computed for same-key-different-body
detection; what two simultaneous retries of the same key do (one wins the insert — the other must
read the winner's result, never write a second reservation).

### 4 · Per-user limit, holds, expiry
Limit enforced **inside the claim transaction** — a separate count-then-insert is the same race
as read-then-write. Time-boxed hold plus owner-only `POST /reservations/{id}/cancel`.

Decide: lazy expiry (expired holds treated as available inside the claim query) vs a sweeper job.
Lazy is what keeps invariant #2 true *during* the burst; a sweeper alone leaves a window. Release
is itself a guarded update on expected owner and state, so it can never resurrect a seat already
confirmed to someone else.

### 5 · API, auth, error mapping

| Method | Path | Who |
|---|---|---|
| POST | `/shows` | admin |
| GET | `/shows/{id}` | any |
| POST | `/shows/{id}/reserve` | user |
| POST | `/reservations/{id}/cancel` | owner |
| GET | `/healthz` · `/readyz` · `/metrics` | open |

Identity is token-derived; a body `user_id` is ignored outright, not validated. Admin vs user by
role claim. A global handler maps every domain exception to its reason code — constraint
violations, lock timeouts and serialization failures are caught and translated, never escaping as
500s. Money is `long` paise throughout.

### 6 · Observability
`/healthz` liveness, cheap, no dependencies. `/readyz` **fails closed** — pings Postgres, 503
when unreachable. Prometheus: `reservations_confirmed_total`,
`reservations_declined_total{reason=…}`, `seats_available` gauge, reserve-path latency histogram.
Metrics must reconcile with `GET /shows/{id}`. Structured JSON logs carrying a request id from an
inbound header or generated per request — log the decision and reason, not the body.

Decide: what pages at 2am. (5xx rate, readiness flapping, invariant drift.)

### 7 · Container & deploy
Multi-stage Dockerfile, non-root. `docker-compose.yml` with app + Postgres so a clean checkout
runs the way it deploys. Free tier (Render/Railway/Fly) + managed Postgres. Verify cold start:
healthy from sleep, `/readyz` green only after the DB is reachable, migrations not racing a second
instance. Verify a clean clone builds — this is how strong submissions most often fall over.

### 8 · Burst script — `./scripts/burst.sh <BASE_URL>`
Fresh show, then: hot-seat storm (hundreds of users, one seat), idempotent replays, same key with
different seats, one user × 10 parallel reserves against limit 4, and a spoofed-body-identity
probe. Prints outcome distribution (confirmed / declined-by-reason / 5xx) and the final
reconciliation against `GET /shows/{id}`.

Pass: exactly one 201 per hot seat · zero 5xx · invariant holds to the unit.

### 9 · `WRITEUP.md`
The atomic decision (mechanism named, why race-free, multi-seat deadlock avoidance) · idempotency
(storage, exactly-once, same-key-different-body) · holds & expiry · consistency vs availability
under a partition · observability and the 2am page · **AI usage, directed vs decided, specific and
honest** · what's next.

---

## Open decision: where (if anywhere) Redis goes

Parked until the end of Step 2. Three candidates, increasing risk:

1. **No Redis.** Postgres alone owns the atomic decision. Fewest moving parts; the assignment
   encourages exactly this.
2. **Redis caches `GET /shows/{id}` only.** Read-heavy seat map, tolerant of ~1s staleness, never
   gates a write. The claim stays a single atomic step in one store.
3. **Redis owns the claim** (Lua / `SET NX`). Genuinely atomic, but splits the system of record —
   needs an answer for durability across a free-tier restart, and for how seat state in Redis and
   reservation rows in Postgres stay reconciled with no transaction spanning both.

A cache consulted *before* a Postgres write is not on this list: that is precisely the
read-then-write the spec calls out.

Resolve it with a measurement, not a hunch — optimising before there's a number is guessing, and
"I measured it" beats "I assumed it" in the interview.

---

## Dependencies

webmvc · validation · data-jpa · postgresql · flyway (core + `flyway-database-postgresql`) ·
security · jjwt 0.12.6 · actuator · micrometer-registry-prometheus
Test: webmvc-test · spring-boot-testcontainers · testcontainers postgresql + junit-jupiter
(Boot 4.1.1 doesn't manage the Testcontainers BOM, so it's imported explicitly.)

Two consequences:
- `starter-security` locks down every endpoint by default — `/healthz`, `/readyz`, `/metrics`
  need explicit permit rules, or readiness fails closed for the wrong reason.
- `data-jpa` means the app won't boot without a datasource. Postgres via compose, or
  Testcontainers in tests.

---

## Running

```bash
./mvnw package                            # build
docker compose up --build                 # app :8080 + postgres      (Step 7)
./scripts/burst.sh http://localhost:8080  # local burst               (Step 8)
./scripts/burst.sh <LIVE_URL>             # against the deploy
```

## Deliverables

- [ ] Public repo, incremental commit history
- [ ] Live URL, healthy from cold start
- [ ] `./scripts/burst.sh <BASE_URL>` documented above
- [ ] Metrics + log access
- [ ] `WRITEUP.md`
