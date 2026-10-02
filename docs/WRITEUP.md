# WRITEUP

Seat reservation service — Java 21 · Spring Boot 4.1.1 · PostgreSQL 18 · Docker.

Measured locally at 20,000 concurrent reservations against a fresh 1000-seat show:

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

## 1. The atomic decision

**Mechanism: a single conditional `UPDATE` guarded on current state, with the affected
row count as the verdict.** `SeatRepository.claimSeats`:

```sql
UPDATE seats
   SET status = 'HELD', claimed_by = :userId,
       hold_expires_at = :holdExpiresAt, reservation_id = :reservationId
 WHERE show_id = :showId
   AND id IN (:seatIds)
   AND (status = 'AVAILABLE'
        OR (status = 'HELD' AND hold_expires_at <= :now))
```

### Why it is race-free

The test and the write are the same statement, so there is no window between them.

Postgres takes a row-level write lock for the duration of the `UPDATE`. A second
transaction targeting the same row **blocks**; when the first commits, the second
re-evaluates its `WHERE` clause **against the new row version** — this is the documented
`READ COMMITTED` behaviour for `UPDATE`. The seat is now `HELD`, the predicate no longer
matches, zero rows are affected, and the caller gets a 409.

`claimed != seatIds.size()` is therefore the only check needed. 500 requests for A12
produce exactly one affected row in exactly one transaction.

### Alternatives considered

| Option | Why not |
|---|---|
| `SELECT` then `UPDATE` | The classic double-sell. Both readers see AVAILABLE. |
| Separate claims table + unique partial index | Same guarantee, but adds a join to the hottest read (the seat map) and the index predicate is easy to get subtly wrong. |
| Redis `SET NX PX` lock | Advisory only — it does not guard the Postgres write. If the TTL lapses mid-transaction two holders exist and nothing rejects the second. Adds two round trips and a second store that can be down. |
| `SERIALIZABLE` | Correct, but converts contention into serialisation failures that must be retried — strictly more work than the guarded update. |

### Multi-seat and deadlock avoidance

**All-or-nothing.** Fewer rows affected than requested → `SeatTakenException` → the whole
transaction rolls back, including the reservation row and the idempotency key. A buyer
asking for two adjacent seats does not want one of them.

**Deadlock avoidance: deterministic lock order.** `[A12,A13]` and `[A13,A12]` arriving
together would deadlock if each locked in request order. `findSeatIdsByShowIdAndLabels`
ends `ORDER BY id ASC`, so every request acquires the same rows in the same order and one
simply waits.

Ordering is done **in SQL rather than in Java** so it is guaranteed at the source and
cannot be lost by a caller that forgets to sort. Sorting by `id` (a `BIGINT`) rather than
label avoids any question of string collation agreeing between Java and Postgres.

### Keeping the lock window small

`reserve` runs as one transaction ordered deliberately:

```
1 resolve show, one Instant from the Clock bean
2 idempotency lookup        -> replay or conflict, early return
3 labels -> seat ids        -> sorted ASC by the query
4 per-user limit            -> inside this transaction
5 INSERT reservation        -> uncontended row
6 INSERT idempotency key    -> uncontended row
7 UPDATE seats (the claim)  -> CONTENDED, LAST
8 COMMIT
```

Lock hold time runs from step 7 to the commit. Steps 5 and 6 touch rows nobody contends
for, so they cost nothing up front. The schema enforces this order anyway: `ck_seats_held`
requires `reservation_id IS NOT NULL`, so the reservation must exist before a seat can be
held.

---

## 2. Idempotency

### Where the key is stored

Table `idempotency_keys`, **primary key `(user_id, idempotency_key)`**, with
`request_fingerprint` and `reservation_id`.

Scoped per user so two users choosing the same key string cannot collide, and so a leaked
key cannot read someone else's reservation.

### How exactly-once is enforced

**By the primary key, not by application code.** Insertion is:

```sql
INSERT INTO idempotency_keys (...) VALUES (...)
ON CONFLICT (user_id, idempotency_key) DO NOTHING
```

and the affected row count decides. An application-level "have I seen this key?" check
would be read-then-write — the same race the seat claim exists to eliminate.

| Case | Behaviour |
|---|---|
| Key unseen | Insert affects 1 row, reservation proceeds. |
| Key seen, fingerprint matches | Original reservation loaded and returned. Nothing new written. |
| Key seen, fingerprint differs | 409 `idempotency_conflict`. |
| Key being inserted concurrently | Insert affects 0 rows; the winner's row is invisible under READ COMMITTED → 409 `idempotent_in_flight`, retry. |
| Key row exists with null reservation | Same in-flight 409. Never a null dereference. |

### Same key, different body

`request_fingerprint` is SHA-256 over `showPublicId + "|" + sorted(labels)`.

**Sorted deliberately:** `["A12","A13"]` and `["A13","A12"]` are the same request, so a
client retrying with a reordered array gets a replay rather than a spurious conflict. Only
a genuinely different seat set produces 409.

### The in-flight case

Two identical retries arrive together. One inserts the key; the other's insert conflicts
and returns 0, but it cannot read the winner's uncommitted row. Rather than spin or
return a 500, it returns a 409 telling the client to retry — truthful, bounded, and a
domain outcome rather than a server error.

---

## 3. Holds and expiry

**Model: time-boxed hold plus owner-only cancel.** `reserve` creates a `HELD` reservation
with `hold_expires_at = now + holdTtlSeconds` (default 120).
`POST /reservations/{id}/confirm` promotes it, `POST /reservations/{id}/cancel` releases it.

### Expiry is computed, not stored

A lapsed hold is **not** rewritten. Every query that reads seat state applies the same
predicate:

- `claimSeats` — an expired hold is claimable.
- `countActiveSeatsForUser` — an expired hold does not count toward the limit.
- `countByEffectiveStatus` — an expired hold counts as available.
- `Seat.effectiveStatus(now)` — the same rule in Java, for the seat map.

**Benefit:** the reconciliation invariant is true *continuously*, not merely after a
sweeper runs. A sweeper-only design has a window in which a seat is neither usable nor
counted as free, and `GET /shows/{id}` would disagree with reality mid-burst.

**Cost:** four places encode the same predicate and must change together.

`HoldExpiryService` sweeps every 30s to settle the stored rows, but **correctness does not
depend on it**. It is safe to run on several instances: both statements are guarded on
`status = 'HELD' AND expiry <= now`, so a second sweeper affects zero rows.

### A release cannot resurrect a confirmed seat

Every release is itself a guarded update:

```sql
UPDATE seats SET status = 'AVAILABLE', ...
 WHERE reservation_id = :reservationId AND claimed_by = :userId AND status = 'HELD'
```

Ownership and state are checked in the same atomic statement as the write. A seat already
`CONFIRMED` to someone else cannot match. Zero rows affected is a decline, not an error.

---

## 4. Consistency vs availability under a partition

**This service chooses consistency. It is CP, deliberately.**

A seat is a unique physical thing. Selling A12 twice is not an inconvenience to reconcile
later — it is two people at one chair. There is no compensating transaction that makes
that acceptable, so availability is the thing to give up.

Concretely:

- **App cannot reach Postgres** → `/readyz` fails closed with 503, the platform stops
  routing traffic, and in-flight requests get a 4xx/5xx rather than a guess. The service
  refuses to sell rather than sell blind.
- **Single primary, no multi-master.** All writes go to one Postgres. There is no path
  where two nodes independently decide who owns A12, because only one node ever decides.
- **No cache in the write path.** A cache consulted before the write would be a second
  source of truth that can diverge during a partition. The only caching considered was
  `GET /shows/{id}`, which is read-only and tolerates staleness.
- **If a read replica were added**, it would serve the seat map only. A reservation would
  never be decided from replica data, because replication lag is exactly a partition in
  miniature.

The honest trade: during a database outage the service is **down for writes**. For a
ticketing system that is correct. A system where a wrong answer is cheap would choose
differently.

---

## 5. Observability

### Endpoints

| Endpoint | Purpose |
|---|---|
| `/healthz/liveness` | cheap, no dependencies — a hung DB must not get the container killed |
| `/healthz/readiness` | actually queries Postgres, **503 when unreachable** |
| `/metrics` | Prometheus exposition |

### Metrics

- `reservations_confirmed_total`
- `reservations_replayed_total`
- `reservations_declined_total{reason="seat_taken" | "per_user_limit" | "idempotency_conflict" | ...}`
- `seats_available` / `seats_held` / `seats_confirmed` / `seats_total`, tagged by show
- `reservation_reserve_duration` histogram, plus HTTP server percentiles

Two decisions worth stating:

**Every counter is pre-registered at zero** for all `DeclineReason` values. An absent
Prometheus series renders as "no data", which is indistinguishable from "service dead".

**Decline counters increment inside `GlobalExceptionHandler.respond()`** — the same line
that sets the HTTP status. The counter and the response cannot drift.

**Gauges refresh on a 5s schedule, not at scrape time.** A scrape must never run a query
against the hot seats table; otherwise monitoring adds load to the system it monitors, and
a scrape storm during a burst makes things worse. They use the same
`countByEffectiveStatus` query the API uses, so `/metrics` and `GET /shows/{id}` cannot
disagree.

### Logs

ECS JSON via Spring Boot's built-in structured logging. `RequestIdFilter` honours an
inbound `X-Request-Id` or generates one, puts it in MDC, echoes it on the response, and
clears it in a `finally` — Tomcat reuses pooled threads, and a leaked MDC entry would
mislabel the next request. The inbound header is length-capped and charset-restricted,
since it lands in every log line.

Declines log at INFO without stack traces, and `DomainException` suppresses stack capture
entirely (`super(message, null, false, false)`). 20,000 declines should not produce 20,000
stack traces. Anything at ERROR during a burst is a real bug, findable by request id.

### What I would want to be paged for at 2am

1. **Any 5xx rate above zero on `/shows/*/reserve`.** Declines are 4xx by design; a 5xx
   means a failure mode that was not classified. This is the page.
2. **Reconciliation drift** — `seats_available + seats_held + seats_confirmed != seats_total`
   for any show. This means the invariant broke, which means a double-sell is possible.
   Highest severity even if no customer has complained yet.
3. **Readiness flapping** — repeated 503s mean the DB connection is unstable; the service
   is up but cannot sell.
4. **`reservations_declined_total{reason="rejected_overload"}` rising** — load shedding is
   working, but it means capacity is short and revenue is being turned away.

Deliberately **not** pages: a high `seat_taken` rate (that is a sell-out, the happy path at
peak) or p99 latency during an on-sale window (queueing is expected behaviour).

---

## 6. AI usage

Claude (Claude Code) was used throughout, in two distinct modes.

**Directed** — I decided, it typed: package layout, repository query shapes, DTO and
controller boilerplate, the Dockerfile, the Postman collection, the burst harness.

**Decided together** — I proposed Redis for caching and later as a distributed lock; it
pushed back on both with the specific failure modes (a lock TTL lapsing mid-transaction
leaves two holders; the lock is advisory and does not guard the Postgres write), and I
dropped it. It also flagged a real bug where my `confirm` called `cancelOwnedHold`, and
the self-referencing `${SPRING_DATASOURCE_URL}` placeholder that would have broken the
deploy.

**Mine** — the model choice (hold vs immediate confirm), all-or-nothing semantics, lazy
vs swept expiry, and the decision to stay on a single datastore. I also stripped a
hand-rolled UUIDv7 generator it added, because the marginal index locality was not worth
carrying code I would not choose to write myself.

---

## 7. What I would do next

In priority order:

1. **Concurrency tests in JUnit with Testcontainers.** The burst proves behaviour at the
   HTTP boundary; it does not pin it. N threads on one seat behind a `CountDownLatch`,
   asserting exactly one winner, is the regression test this design needs and currently
   lacks. This is the largest real gap.
2. **Close the per-user limit race.** The count is taken inside the transaction but reads
   rows a concurrent transaction may be about to claim, so two parallel multi-seat requests
   from one user can each pass the check. The single-seat case holds because the claim
   itself serialises. A proper fix is a lock on a per-user-per-show row, or a deferred
   constraint.
3. **Consistent 401/403 bodies.** Authorisation failures raised inside the Spring Security
   filter chain bypass `GlobalExceptionHandler`, so they return Spring's default shape
   rather than `ErrorResponse`. Needs an `AuthenticationEntryPoint` and
   `AccessDeniedHandler`.
4. **PgBouncer in transaction mode.** The real ceiling is
   `instances × pool_size < max_connections`. Transactions here are short and hold no
   session state, so transaction pooling is safe and breaks that ceiling without code
   changes.
5. **Cache `GET /shows/{id}`.** ~95% of traffic, tolerates a second of staleness, never
   gates a write. The one place a cache is honest.
6. **Shard by `show_id` if volume demanded it.** Shows are fully independent — no
   reservation spans two shows, so there are no cross-shard transactions. A single show is
   capped by its seat count anyway, so the scaling axis is the number of shows.
7. **Make `AdminBootstrap` idempotent under races** — two instances booting against a
   fresh database both pass `existsByUsername` and one fails startup.

---

## Known deviations from the brief

Stated rather than hidden:

- **Reserve returns `"status": "held"`, not `"confirmed"`.** The brief's example shows
  `confirmed`, but it also explicitly permits "a time-boxed hold that auto-expires" as the
  release model. I took that option so an abandoned checkout cannot lock a seat forever;
  `POST /reservations/{id}/confirm` promotes a hold.
- **Readiness lives at `/healthz/readiness`**, not `/readyz`.
- **`POST /shows` without a token returns 403, not 401** — Spring Security's default for an
  anonymous request against a role-protected route.
