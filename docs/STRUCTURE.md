# Package structure & LLD rules

I write the design and review; the implementation is mine to type. This file says what belongs
where and which rules a review will check against.

## Layers

```
com.social.seat_reservation
├── api/
│   ├── controller/      HTTP only: bind, validate, delegate, map to status. No business rules.
│   ├── dto/request/     Inbound records. Bean Validation annotations live here.
│   ├── dto/response/    Outbound records. Never leak an entity past this boundary.
│   └── error/           GlobalExceptionHandler, ErrorResponse, reason-code mapping.
├── domain/
│   ├── model/           Entities. Behaviour lives with the data it guards.
│   ├── enums/           SeatStatus, ReservationStatus, DeclineReason.
│   └── exception/       Domain exceptions, each carrying its DeclineReason.
├── repository/          Spring Data interfaces + the hand-written atomic claim query.
├── service/             Transaction boundaries and the decision logic.
├── security/            Token parsing, the authenticated principal, role checks.
├── config/              Beans and wiring only. No logic.
└── observability/       Request-id filter, metric recorders, readiness indicator.
```

`src/main/resources/db/migration` — Flyway `V1__…sql` onward. Schema is versioned, never
auto-generated. `ddl-auto` stays `validate`.

`scripts/` — `burst.sh`. `docs/` — design notes. `WRITEUP.md` stays at repo root.

## Rules a review will hold you to

**Dependencies point inward.** `api → service → repository → domain`. A controller that imports a
repository, or a domain class that imports anything from `api`, is a finding.

**One transaction, one decision.** The atomic claim is a single `@Transactional` method. No
network call, no metric push, no logging-with-side-effects inside it. Transactions stay short —
under contention, lock hold time *is* your throughput ceiling.

**No `@Transactional` on a controller.** The boundary is the service.

**Entities do not escape.** Controllers return DTOs. An entity serialized straight to JSON leaks
schema and drags lazy-loading into the HTTP layer.

**No interface without a second implementor.** `ReservationService` + `ReservationServiceImpl`
with one impl is ceremony, not abstraction. Introduce the interface when a real second
implementation or test seam exists.

**Every decline is a typed domain exception** carrying a `DeclineReason`. The handler maps
reason → status → metric label. One vocabulary across API, metrics, and logs — so a 409 in a log
line and a counter increment are provably the same event.

**Constructor injection, final fields.** No `@Autowired` on fields.

**Money is `long` paise.** A `double` anywhere near an amount is a finding.

**Fail closed.** Readiness that can't reach the DB returns 503. Auth that can't verify a token
rejects. Never default to "probably fine".

## Testing strategy

Four tiers, each with a different job:

| Tier | Where | Job |
|---|---|---|
| Unit | `service/`, `api/` | Decision logic and mapping in isolation. Fast, no Spring. |
| Slice | `api/` | Controller + validation + error mapping. MockMvc, mocked service. |
| Repository | `repository/` | The atomic claim query against real Postgres (Testcontainers). H2 will not reproduce Postgres locking. |
| Concurrency | `concurrency/` | The ones that matter. Real DB, real threads. |

`support/` holds the Testcontainers base class, a token/auth fixture, and builders.

**The concurrency tests are the deliverable.** Each asserts an invariant, not an implementation:

- N threads, one seat → exactly one success, N−1 clean declines, zero exceptions.
- N threads, same idempotency key → exactly one reservation row.
- Same key, different seat set → 409, and nothing extra written.
- One user, 10 parallel reserves, limit 4 → at most 4 held.
- Multi-seat overlapping sets in opposite orders → no deadlock, no partial writes.
- After any burst → `available + held + confirmed == total_seats`.

Use a `CyclingBarrier`/`CountDownLatch` so threads actually collide rather than queue. A
concurrency test that passes because the threads ran sequentially is worse than no test.

## Commit discipline

Commit at each point below, not in a lump at the end — the history is reviewed.

- [ ] `chore: project skeleton, dependencies, package structure`
- [ ] `docs: requirements, invariants, and error contract`
- [ ] `feat: flyway schema for shows, seats, reservations, idempotency keys`
- [ ] `test: repository tests for the atomic claim`
- [ ] `feat: atomic seat claim via conditional update`
- [ ] `test: hot-seat concurrency — one winner, N-1 declines`
- [ ] `feat: idempotent reservations keyed on (user, key)`
- [ ] `test: concurrent retries with the same key`
- [ ] `feat: per-user limit enforced in the claim transaction`
- [ ] `feat: holds, expiry, and owner-only cancel`
- [ ] `feat: token-derived identity and role checks`
- [ ] `feat: global error handler mapping declines to 4xx`
- [ ] `feat: liveness, readiness, prometheus metrics`
- [ ] `feat: request-id filter and structured logging`
- [ ] `chore: dockerfile and compose`
- [ ] `test: one-command burst script with reconciliation`
- [ ] `chore: deploy config`
- [ ] `docs: writeup`

Rule of thumb: if the message needs an "and", it's two commits. Test and the code it covers may
share a commit, or test-first in its own — both read well in history. `wip` does not.
