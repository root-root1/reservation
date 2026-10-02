# Tasks

What goes in each file. No TODOs in the source — the spec lives here.
Work top to bottom. Commit where marked. Design rules: `docs/STRUCTURE.md`.

---

## Block A — domain vocabulary

**`domain/enums/SeatStatus`** — `AVAILABLE`, `HELD`, `CONFIRMED`. Nothing else; these three are
the terms of the reconciliation invariant.

**`domain/enums/ReservationStatus`** — the lifecycle of a reservation. Decide whether a
reservation is born `HELD` and later `CONFIRMED`, or goes straight to `CONFIRMED`, plus the
terminal states (`CANCELLED`, `EXPIRED`). Your answer changes what the reserve endpoint returns.

**`domain/enums/DeclineReason`** — one constant per row of the error-contract table in the
README, each carrying its HTTP status and its wire string. This enum is the single source of
truth: the handler maps from it, the metric label comes from it, the log field comes from it.

**`domain/exception/DomainException`** — base, holds a `DeclineReason`. Every subclass supplies
its reason. Nothing in the service throws a bare `RuntimeException`.
Subclasses: `SeatTaken`, `PerUserLimitExceeded`, `IdempotencyConflict`, `NotOwner`,
`ShowNotFound`, `ReservationNotFound`.

Consider: disable stack-trace capture on these (`super(msg, null, false, false)`). At 20k
declines/burst, filling in stack traces is measurable cost for traces nobody reads.

> **Commit:** `feat: domain vocabulary — seat/reservation states and decline reasons`

---

## Block B — schema

**`resources/db/migration/V1__init.sql`** — the whole schema in one migration.

- `shows` — id, name, price_paise `BIGINT`, per_user_limit, total_seats, created_at.
- `seats` — id, show_id, label, status, held_by (nullable), held_until (nullable),
  reservation_id (nullable). **Unique `(show_id, label)`.**
- `reservations` — id, show_id, user_id, status, amount_paise `BIGINT`, created_at, expires_at.
- `reservation_seats` — join; unique on seat_id if a seat can appear once across all active
  reservations.
- `idempotency_keys` — user_id, key, request_fingerprint, reservation_id, created_at.
  **Unique `(user_id, key)`.** This constraint is what enforces exactly-once; application code
  must not be the thing checking.

Before you write it, answer:
- Does the claim live as a `status` column on `seats`, or as a row in a separate `seat_claims`
  table with a unique partial index on active claims? Pick one, say why the other loses.
- Which indexes does the hot path touch? Any index not on that path is write cost during the
  burst.
- `held_until` semantics: is an expired hold still `HELD` in the column until something reaps it?
  **This is the open Step 1 question** — it decides whether invariant #2 is continuously true or
  only true after a sweeper runs.

Set `spring.jpa.hibernate.ddl-auto=validate` in `application.properties` at the same time.

> **Commit:** `feat: flyway schema for shows, seats, reservations, idempotency keys`

---

## Block C — entities & repositories

**`domain/model/*`** — JPA entities mirroring the migration. Behaviour that guards a field lives
on the entity, not in the service. No `double` anywhere near paise.

**`repository/SeatRepository`** — the important one. It carries the **atomic claim query**:
a single guarded statement, hand-written, that flips seats to held only if they are currently
claimable, and reports how many rows it actually changed. If the count is less than the number of
seats requested, the caller lost — and the transaction rolls back (all-or-nothing) or keeps what
it got (best-effort), per your Step 2 decision.

It must not be possible to call "is this seat free?" and then "take it" as two statements.

**`repository/IdempotencyKeyRepository`** — insert-and-catch-the-constraint-violation, plus a
lookup by `(user_id, key)`.

Others are plain Spring Data interfaces.

**Test first here — `repository/SeatClaimRepositoryTest`** against Testcontainers Postgres
(`support/PostgresTestBase`). H2 will not reproduce Postgres row locking; a green H2 test proves
nothing. Cases: claim an available seat succeeds; claim a held seat changes zero rows; claim a
mixed set reports a partial count; claim an expired hold succeeds if lazy expiry is your model.

> **Commit:** `test: repository tests for the atomic claim`
> **Commit:** `feat: atomic seat claim via conditional update`

---

## Block D — the reserve path

**`config/ReservationProperties`** — `per_user_limit` default, hold TTL. Externalised, not
hardcoded, because you will be asked to change it live in the interview.

**`config/ClockConfig`** — expose a `Clock` bean. Every `now()` goes through it, so expiry is
testable without sleeping.

**`service/ReservationService`** — one `@Transactional` method is the whole decision:

1. Resolve show; reject unknown.
2. Idempotency: attempt to claim the key. Existing key + matching fingerprint → return the
   original reservation. Existing key + different fingerprint → `IdempotencyConflict`.
3. Per-user limit, checked **inside this transaction**, against the same rows the claim touches.
   A count-then-insert outside the transaction is the same race you removed from the seat claim.
4. Seats sorted into a deterministic order, then the atomic claim.
5. Row count short of the request → `SeatTaken` and roll back (all-or-nothing).
6. Write reservation, link seats, bind the idempotency key to the reservation.

Nothing else in this method. No metrics, no HTTP, no outbound call — lock hold time is your
throughput ceiling under contention. Record metrics in the layer above.

Deadlock: two requests for `[A12,A13]` and `[A13,A12]` will deadlock unless both acquire in the
same order. Sorting is the fix; know *why* it's the fix before you write it.

**`service/HoldExpiryService`** — whatever your expiry model needs. If lazy, this may only exist
to reap dead rows for tidiness, and the correctness lives in the claim query itself.
A release is a **guarded** update on expected owner and status — it must be incapable of
resurrecting a seat already confirmed to someone else.

**`service/ShowService`** — create show (admin) and read show state with per-seat status and
counts. The counts must be computed so that `available + held + confirmed == total_seats` reads
true even mid-burst.

Tests, in order, each one asserting an invariant rather than an implementation:

| Test | Asserts |
|---|---|
| `service/ReservationServiceTest` | decision logic in isolation, mocked repos |
| `concurrency/HotSeatConcurrencyTest` | N threads, 1 seat → exactly 1 success, N−1 declines, 0 exceptions |
| `concurrency/IdempotencyConcurrencyTest` | N threads, same key → exactly 1 reservation row; different body → conflict |
| `concurrency/PerUserLimitConcurrencyTest` | 1 user, 10 parallel reserves, limit 4 → at most 4 held |
| `concurrency/MultiSeatDeadlockTest` | overlapping sets in opposite orders → no deadlock, no partial writes |
| `concurrency/ReconciliationInvariantTest` | after any burst, counts sum to total_seats |

Gate every concurrency test behind a `CountDownLatch` so the threads genuinely collide. One that
passes because the threads quietly ran sequentially is worse than no test.

> **Commit:** `test: hot-seat concurrency — one winner, N-1 declines`
> **Commit:** `feat: idempotent reservations keyed on (user, key)`
> **Commit:** `feat: per-user limit enforced in the claim transaction`
> **Commit:** `feat: holds, expiry, and owner-only cancel`

---

## Block E — API surface

**`api/dto/request/CreateShowRequest`**, **`ReserveRequest`** — records, Bean Validation on the
fields. `ReserveRequest` carries seats and the idempotency key. If it also carries a `user_id`,
that field is **ignored**, not validated.

**`api/dto/response/*`** — records. No entity ever crosses this boundary.

**`api/controller/ShowController`** — `POST /shows` (admin), `GET /shows/{id}`.
**`api/controller/ReservationController`** — `POST /shows/{id}/reserve`,
`POST /reservations/{id}/cancel`. Bind, delegate, map status. No `@Transactional`, no repository
import, no business rule.

**`api/error/ErrorResponse`** — reason string, message, request id.
**`api/error/GlobalExceptionHandler`** — `DomainException` → its reason's status. Also catch what
the database throws under load: constraint violations, lock timeouts, serialization failures.
Each is either translated to a domain decline or retried — **none may escape as a 5xx**. That
clause is a graded line item.

**`security/AuthenticatedUser`**, **`Role`**, **`TokenService`**, **`JwtAuthenticationFilter`** —
identity comes from the token subject. The service signature should take the authenticated
principal, so there is no code path where a body field could supply a user id.

**`config/SecurityConfig`** — permit `/healthz`, `/readyz`, `/metrics`; authenticate the rest;
admin role on `POST /shows`. Stateless, no sessions, CSRF off for a token API.

Tests: `api/ReservationControllerTest` (MockMvc, mocked service — validation and status mapping),
`api/GlobalExceptionHandlerTest` (every `DeclineReason` maps to the documented status), plus a
spoofing test proving a body `user_id` cannot change the acting identity.

> **Commit:** `feat: token-derived identity and role checks`
> **Commit:** `feat: reserve/cancel/show endpoints`
> **Commit:** `feat: global error handler mapping declines to 4xx`

---

## Block F — observability

**`observability/RequestIdFilter`** — read an inbound correlation header or generate one; put it
in MDC; echo it on the response and into `ErrorResponse`. Clear the MDC in a `finally`, or you
will leak ids across pooled threads.

**`observability/ReservationMetrics`** — `reservations_confirmed_total`,
`reservations_declined_total{reason=…}` labelled from `DeclineReason`, `seats_available` gauge,
reserve latency histogram. Called from the controller/handler layer, never inside the claim
transaction. Metrics must reconcile with `GET /shows/{id}`.

**`observability/DatabaseReadinessIndicator`** — `/readyz` actually queries Postgres and returns
503 when it can't. Fails closed. `/healthz` stays cheap and dependency-free, or a hung DB will
get your container killed instead of just marked unready.

JSON log encoder in `application.properties`/logback. Log the decision and reason code, not the
request body.

> **Commit:** `feat: liveness, readiness, prometheus metrics`
> **Commit:** `feat: request-id filter and structured logging`

---

## Block G — ship

**`Dockerfile`** — multi-stage, non-root, JRE base, layered jar.
**`docker-compose.yml`** — app + Postgres; a clean checkout runs the way it deploys.
**`scripts/burst.sh <BASE_URL>`** — fresh show, hot-seat storm, idempotent replays, same key with
different seats, 1 user × 10 parallel against limit 4, spoofed-identity probe. Prints the outcome
distribution and the final reconciliation.
Pass: exactly one 201 per hot seat · zero 5xx · invariant exact.

Deploy, then verify **from a cold start** that `/readyz` goes green only after the DB is up, and
that migrations don't race a second instance.

**`WRITEUP.md`** — contents listed in the README.

> **Commit:** `chore: dockerfile and compose`
> **Commit:** `test: one-command burst script with reconciliation`
> **Commit:** `chore: deploy config`
> **Commit:** `docs: writeup`
