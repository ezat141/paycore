# PayCore M3 — Design

**Transactional outbox, Kafka, and a payment history service**
Date: 2026-10-03 · Status: approved in design review, awaiting spec review · Revision 1

---

## 1. Purpose

M0–M2 built a payment service whose guarantees all live in PostgreSQL and in the acquirer
reference: a client retry can neither charge a card twice nor lose a charge that already happened
(`2026-09-27-paycore-m0-m2-design.md`, §5 and §7).

M3 makes every payment status change visible to other services, without weakening any of that and
without a distributed transaction. It adds three things:

- **A transactional outbox in payment-service.** Every committed status change writes one event
  in the same database transaction.
- **A relay that publishes those events to Kafka**, at least once.
- **A new `history-service`** that consumes the events idempotently, stores an append-only log of
  status changes, and serves it through a secured read API.

Settlement (M4), resilience and reconciliation (M5), metrics (M6) and gateway routing (M7) remain
out of scope.

### 1.1 Departures from the M0–M2 roadmap

The M0–M2 roadmap (§13 there) sketched M3 as "Kafka keyed by merchant; history-service
consuming through an inbox table". Design review changed two things.

- **Events are keyed by payment id, not merchant.** History needs order per payment only. Keying
  by merchant would make one large merchant a hot partition, and would cap consumer parallelism at
  the merchant count, for no correctness M3 needs. M4 settlement will rely on idempotency, not on
  cross-payment order.
- **No separate inbox table.** The history table's primary key is the event id, so
  `INSERT … ON CONFLICT (event_id) DO NOTHING` *is* the deduplication. A second table would
  duplicate it.

---

## 2. Approaches considered

| Approach | Verdict |
|---|---|
| **A. Outbox + in-process polling relay** | **Chosen.** The event is written atomically with the change. The relay is code we own, testable with Testcontainers. Trade-off in §5.2. |
| B. Outbox + Debezium CDC (Kafka Connect tails the WAL) | Rejected for M3. No relay code and lower latency, but it adds Kafka Connect and WAL configuration, and the logic moves into connector configuration. It is the upgrade path at scale. |
| C. Publish after commit (`@TransactionalEventListener(AFTER_COMMIT)`), no outbox | Rejected. A crash between commit and publish loses the event. Publishing before commit emits events for changes that then roll back. "Change committed ⇔ event recorded" cannot hold without a distributed transaction. |

---

## 3. Architecture

| Service | Port | Owns | New in M3 |
|---|---|---|---|
| AuthCore | `:8080` | identity, JWTs, JWKS | — |
| payment-service | `:8083` | payments, idempotency keys, **outbox** | outbox table, `PaymentEvents`, `OutboxRelay`, Kafka producer |
| acquirer-simulator | `:8091` | simulated card network | — |
| **history-service** | `:8084` | **payment history** | new module |
| Kafka (KRaft, single node) | host `:9094` | topic `paycore.payment-events` and its DLT | new |
| Postgres (payments) | host `:5433` | `paycore_payments` | `outbox_events` table |
| **Postgres (history)** | host `:5434` | `paycore_history` | new container |
| Redis | host `:6380` | idempotency fast path | — |

Host port 9092 belongs to AuthCore's compose, so PayCore's Kafka uses 9094. The history database
is a separate container rather than a second database in the existing one, because Postgres init
scripts run only on a fresh volume, and a separate container gives clean database-per-service
isolation.

history-service shares **no code** with payment-service, following the platform rule (share a
wire contract, not code). The contract is pinned by a golden example file (§6.5).

```
client ──JWT──▶ payment-service ──(same tx)──▶ payments + outbox_events     [Postgres :5433]
                        │
                        └─ OutboxRelay (background) ──acks=all──▶ Kafka paycore.payment-events
                                                                       │
                              history-service ◀── @KafkaListener ──────┘
                                    │  ON CONFLICT (event_id) DO NOTHING
                                    ▼
                              payment_history                          [Postgres :5434]
client ──JWT──▶ history-service  GET /payments/{id}/history
```

---

## 4. The event and the outbox write

### 4.1 One event type: `PaymentStatusChanged`

History is an audit trail of status transitions, so one event per *actual* status change is all
it needs.

```json
{
  "eventId": "6f0d…",
  "paymentId": "b8cf…",
  "tenant": "acme",
  "version": 3,
  "fromStatus": "AUTHORIZED",
  "toStatus": "CAPTURING",
  "amount": 1999,
  "currency": "USD",
  "declineCode": null,
  "occurredAt": "2026-10-03T10:15:30.123456Z"
}
```

- `eventId`: a UUID, unique per event, which is the outbox primary key and the consumer's
  deduplication key.
- `version`: the payment row's `@Version` after this change.
- `fromStatus`: `null` for the first event, whose `toStatus` is `PENDING`.
- `occurredAt`: the payment's `updatedAt` after the change.

**Deliberately absent:** `cardToken` and the acquirer authorization id. They do not belong in a
log that other services read.

### 4.2 Outbox table (payments database, Flyway `V3`)

| Column | Type | Notes |
|---|---|---|
| `id` | `uuid` PK | the `eventId` |
| `seq` | `bigserial` NOT NULL UNIQUE | relay ordering (§5.1) |
| `aggregate_id` | `uuid` NOT NULL | payment id, which is the Kafka key |
| `event_type` | `varchar(64)` NOT NULL | `PaymentStatusChanged` |
| `payload` | `jsonb` NOT NULL | the event, §4.1 |
| `created_at` | `timestamptz` NOT NULL DEFAULT `now()` | |
| `published_at` | `timestamptz` NULL | set only after the broker acks |

There is a partial index on `(seq) WHERE published_at IS NULL`, so the relay's scan stays cheap
however large the table grows.

### 4.3 Where events are written

A single component, `PaymentEvents`, is called from `PaymentWrites` inside its existing
transactions. It joins the caller's transaction and never opens its own. The rule is: **emit
exactly when the status before ≠ the status after, compared within the same transaction.**

| `PaymentWrites` method | Emits | Emits nothing when |
|---|---|---|
| `start` (Tx1) | `null → PENDING` for a newly created payment | the key already exists (no payment is created) |
| `recordAuthorizeOutcome` (Tx2) | `PENDING`/`UNKNOWN` → `AUTHORIZED` \| `DECLINED` \| `UNKNOWN` | the payment is `UNKNOWN` and stays `UNKNOWN` (a re-drive with no answer) |
| `claimTransition` | `AUTHORIZED → CAPTURING` \| `VOIDING` | the result is "already done", or a stale re-claim (status unchanged) |
| `completeTransition` | `CAPTURING → CAPTURED`, `VOIDING → VOIDED` | the payment is already done |

**The version must be read after a flush.** JPA increments `@Version` when the persistence
context flushes, normally at commit. Inside the transaction, the in-memory version is still the
*old* one, and a new payment's version is `null`. `PaymentEvents` therefore flushes the
persistence context first, reads the payment's version and `updatedAt`, and then inserts the
outbox row with `JdbcClient`, which joins the same transaction as the idempotency key store does.
A test pins that each event's `version` equals the stored row's version.

### 4.4 Effect on the M0–M2 guarantees

There is no effect on correctness. If the outbox insert fails, its whole transaction rolls back,
exactly like any other database failure at that point:

- **In Tx1,** the request fails before the acquirer is called, so nothing is charged.
- **In Tx2,** the existing handling answers `502` with `paymentId`, and a retry with the same key
  re-drives the same payment.

The payment request path never calls Kafka.

### 4.5 `version` orders events but cannot detect gaps

`version` increases on every commit to the payment row, so it orders one payment's events. It is
**not contiguous**: a stale re-claim (`refreshTransitionClaim`) bumps the version without
emitting an event. History can therefore order by version, but cannot infer a missing event from
a gap.

---

## 5. The relay and Kafka

### 5.1 `OutboxRelay`

`OutboxRelay` is a scheduled component in payment-service, never on a request path. Each pass:

1. In one transaction, claim up to `batch-size` rows:
   `SELECT … FROM outbox_events WHERE published_at IS NULL ORDER BY seq LIMIT :n FOR UPDATE SKIP LOCKED`.
2. Send every claimed row to `paycore.payment-events`. The key is `aggregate_id`, the value is
   `payload`, and headers carry `eventId` and `eventType`.
3. Wait for the broker's acknowledgements, oldest `seq` first.
4. Set `published_at = now()` on the rows **up to the first failure**, then commit. Rows from the
   first failure onward stay unpublished and are retried on the next pass. Any of them that did
   reach the broker will be delivered again, and the consumer absorbs that (§6.2).

**Why `seq` and not `created_at`.** For one payment, `seq` order equals commit order. A change's
outbox insert happens only after its transaction has read the previous change's *committed*
version (optimistic locking makes any other interleaving fail). So the later change's sequence
value is always larger. `created_at` (`now()`) is the transaction's start time, which carries no
such guarantee.

**Settings:**
- `paycore.outbox.poll-interval-ms` (default `500`, a number of milliseconds for `@Scheduled`) and `paycore.outbox.batch-size` (default `100`).
- `paycore.outbox.relay-enabled` (default `true`). Tests turn the schedule off and run passes on
  demand.

### 5.2 Trade-off: the relay's transaction spans the send

The claiming transaction stays open while its batch is in flight to Kafka. It is bounded by the
producer's `max.block.ms` (5 s) plus its delivery timeout (10 s), about 15 s, which the
relay's `send-timeout` covers. The alternative (claim with a lease in one transaction,
send, mark in another) needs lease expiry and recovery logic. That is the same complexity the
idempotency lease already carries, and a background relay does not need it.

The payment request path never waits on the relay. Two relay passes cannot contend for the same
row, because of `SKIP LOCKED`.

### 5.3 Producer

- `acks=all`, `enable.idempotence=true`, and the default `max.in.flight.requests.per.connection`
  (5, which preserves order under idempotence).
- Idempotence removes duplicates *introduced by producer retries*, and keeps per-partition order
  across them. It does **not** remove duplicates from a relay re-send after a failure or crash.
  The consumer does (§6.2).

### 5.4 Topic

`paycore.payment-events` is declared as a `NewTopic` bean with **3 partitions**, alongside
`paycore.payment-events.DLT`.

- Locally the replication factor is **1**, because a single broker allows no more.
- Production needs RF ≥ 3 with `min.insync.replicas=2` for `acks=all` to protect acknowledged
  writes. That is stated in the README, not claimed.

### 5.5 When Kafka is unavailable

- Sends time out, nothing is marked published, and the relay logs a warning and tries again on
  the next pass.
- Payment requests are unaffected (`201`, `502` and so on, as before), and the outbox grows.
- When Kafka returns, the relay drains the backlog in `seq` order.

### 5.6 What the relay guarantees, and what it does not

- **Guaranteed:** every committed event is published to Kafka **at least once**, eventually.
- **Guaranteed with one relay instance:** a payment's events reach Kafka in `seq` order.
- **Not guaranteed with several instances:** `SKIP LOCKED` lets two relays split one payment's
  events and send them concurrently, so Kafka order can differ from commit order. history-service
  is correct regardless (§6.3).

---

## 6. history-service

### 6.1 Module

- New Maven module `history-service`, Spring Boot 4.1, Java 21, servlet stack, port `8084`.
- Database: `paycore_history` on its own Postgres container (host `5434`), with Flyway owning the
  schema.

### 6.2 Storage and consumption

Table `payment_history` (Flyway `V1`):

| Column | Type | Constraint |
|---|---|---|
| `event_id` | `uuid` | **PK**, the deduplication key |
| `payment_id` | `uuid` | NOT NULL |
| `tenant` | `varchar(100)` | NOT NULL |
| `version` | `bigint` | NOT NULL; `UNIQUE (payment_id, version)` |
| `from_status` | `varchar(16)` | NULL only for the first event |
| `to_status` | `varchar(16)` | NOT NULL; CHECK over the 8 statuses |
| `amount` | `bigint` | NOT NULL |
| `currency` | `char(3)` | NOT NULL |
| `decline_code` | `varchar(64)` | NULL |
| `occurred_at` | `timestamptz` | NOT NULL |
| `received_at` | `timestamptz` | NOT NULL DEFAULT `now()` |

There is an index on `(tenant, payment_id, version)` for the read path.

The table is **append-only**. There is no current-status projection, so out-of-order arrival
cannot corrupt anything.

Consumer: a `@KafkaListener` on `paycore.payment-events`, in consumer group `history-service`.

- **Idempotent:** `INSERT … ON CONFLICT (event_id) DO NOTHING`. A relay re-send, a rebalance
  redelivery and a restart mid-stream all leave one row per event.
- **Offset after the database:** the record's offset is committed only after its insert
  commits. A crash between the two causes a redelivery, never a lost event.
- **`auto-offset-reset: earliest`:** a new group replays the topic, so history can be rebuilt.
- **`concurrency: 3`:** the partition count. More would leave consumers idle.

### 6.3 Ordering in history

Rows are read ordered by `version`, so arrival order does not matter. The history for one
payment is correct even when a multi-instance relay or a redelivery reorders its events.

There is no cross-payment ordering, and none is needed.

### 6.4 Failures

| Failure | Handling | Why |
|---|---|---|
| Record cannot be deserialised, or fails validation (unknown status, missing ids, bad currency) | not retried; published to `paycore.payment-events.DLT` | one poison record must not stall its partition |
| Record conflicts with stored history: a different event id for a version already stored, or a value too long for its column (any `DataIntegrityViolationException`) | not retried; published to the DLT | permanent: retrying can never succeed, and blocking would stall every payment on the partition. A correct producer never emits one, since each version is written once. It signals divergence, for example a payments database restored from a backup, and the DLT keeps it visible for a person to inspect. The history keeps the first event stored for that version. |
| History database unavailable | retried with backoff, **without limit** | the event is valid; sending it to the DLT would lose history. Blocking the partition until Postgres returns is correct and keeps per-payment order. |

Records are read as strings and parsed by the service's own `EventParser`, not by a Kafka
deserializer. A malformed or invalid record therefore becomes one non-retryable
`InvalidEventException`, which goes to the DLT, instead of a deserialisation loop.

### 6.5 Read API

`GET /payments/{id}/history` returns the payment's status changes, ordered by `version`:

```json
[
  {"version": 0, "fromStatus": null,         "toStatus": "PENDING",    "occurredAt": "…"},
  {"version": 1, "fromStatus": "PENDING",    "toStatus": "AUTHORIZED", "occurredAt": "…"}
]
```

- **Security, the same rules as payment-service:** an OAuth2 resource server that verifies
  AuthCore JWTs itself (JWKS, pinned issuer). It requires `SCOPE_payments:read` **and** the
  `payments:read` permission, then a non-blank String `tenant` claim. All of this is checked in
  the filter chain before any handler runs. Errors use the platform JSON shape, including an
  `/error` controller.
- **Tenant isolation:** rows are filtered by the caller's tenant. Another tenant's payment, or a
  payment with no history yet, returns `404`, which does not confirm the payment exists.
- **Eventually consistent:** a just-created payment can briefly return `404` or a history that
  has not caught up. The lag is the relay's poll interval plus consumer time.

**Contract.** The golden example `contracts/payment-status-changed.json` sits at the repo root.

- payment-service tests assert that the outbox payload has exactly that example's field names and
  JSON types.
- history-service tests consume that file.

Either side drifting breaks its own build.

---

## 7. Guarantees, link by link

| Link | Guarantee | Mechanism | Not guaranteed |
|---|---|---|---|
| 1. Payment status change | atomic (unchanged) | one Postgres transaction in `PaymentWrites` | — |
| 2. Outbox write | an event exists **⇔** the change committed | same transaction as link 1 | anything about Kafka |
| 3. Kafka publication | **at least once**, eventually, for every committed event | marked published only after the broker ack | exactly-once; real-time; per-payment Kafka order with several relay instances |
| 4. Delivery to history | **at least once** | offset committed after the insert | absence of duplicates (they will happen) |
| 5. History effect | **one row per event** (an effectively-once effect) | `ON CONFLICT (event_id) DO NOTHING` | exactly-once *delivery* |
| 6. History order | correct per payment, whatever the arrival order | keyed and read by `version` | cross-payment order; gap detection |
| 7. Freshness | eventually consistent | poll interval + consumer lag | read-your-writes |

M3 rests on two local transactions plus an at-least-once relay, absorbed by an idempotent consumer.
There is no distributed transaction or two-phase commit, and no claim that Kafka and Postgres are
atomic together.

---

## 8. Invariants and tests

Every test must fail against the breakage named next to it before it is accepted.

| # | Invariant | Test | Breakage it must catch |
|---|---|---|---|
| I1 | Every real status change writes exactly one event, in the same transaction | create, then capture → events `null→PENDING→AUTHORIZED→CAPTURING→CAPTURED`, versions increasing | emitting from only some of the four methods |
| I2 | A rolled-back change leaves no event | Tx2 forced to fail → no `AUTHORIZED` event | outbox write in its own transaction (`REQUIRES_NEW`) |
| I3 | No-op transitions emit nothing | a replay, an already-captured capture, a stale re-claim, and an `UNKNOWN` re-drive with no answer → no new event | emitting on every call |
| I4 | M0–M2 guarantees intact | 20 concurrent identical creates → exactly 2 events (`PENDING`, `AUTHORIZED`); all existing tests pass | emitting before the idempotency check |
| I5 | The payment path never depends on Kafka | Kafka stopped → creates still `201`, outbox grows; Kafka restarted → backlog drained in `seq` order | relay or producer on the request path |
| I6 | At-least-once relay | the broker ack fails after the send → the row stays unpublished and is sent again | marking published before the ack |
| I7 | Per-payment order out of one relay | one payment's events reach Kafka in `seq` order | ordering by `created_at` or by `id` |
| I8 | Idempotent consumer | the same event delivered twice, and a consumer restarted mid-stream → one row each | plain `INSERT` without `ON CONFLICT` |
| I9 | Order-insensitive history | `v3` delivered before `v2` → API returns `v2, v3` | ordering by arrival |
| I10 | A poison record does not block its partition | malformed record → DLT, and the next valid record is stored | no error handler, or retrying everything |
| I11 | A database outage does not lose history | history DB unavailable → retried, stored once it returns, not sent to the DLT | treating database errors as non-retryable |
| I12 | Tenant isolation in history | another tenant's payment → `404`; trust-boundary refusals (no token, scope without permission, no tenant) | querying without the tenant filter |
| I13 | Contract | both services pass against `contracts/payment-status-changed.json` | renaming a field on one side |
| I14 | End to end | README walkthrough: create → capture → `GET /payments/{id}/history` against real Kafka, captured from a live run | — |

Integration tests use Testcontainers (PostgreSQL, Redis, Kafka). Image tags and artifact versions
are verified against Maven Central when planning.

---

## 9. Delivery

- The work is split into tasks on `feature/task-N` branches, merged with `--no-ff` into `master`,
  with commits authored by the repository-local identity.
- The README gains an "Events and history" section, a history-service walkthrough step, and
  every new limitation (§10).
- `docs/specs` (this file) and `docs/plans` hold the design and plan.
- The CV is updated only after M3 is implemented and verified, and only with claims the
  repository proves. Examples of acceptable wording:
  - "transactional outbox to Kafka with an idempotent consumer";
  - "an append-only payment history ordered by version".

  Not acceptable: "exactly-once", "per-account ordering".

---

## 10. Known limitations introduced by M3

- **Local Kafka has replication factor 1.** `acks=all` protects nothing on a single broker.
  Production needs RF ≥ 3 and `min.insync.replicas=2`.
- **Published outbox rows are never deleted.** There is no retention job yet; the partial index
  keeps the relay's scan cheap regardless.
- **Per-payment order in Kafka holds only with a single relay instance.** history-service is
  correct regardless, because it orders by `version`.
- **History is eventually consistent.** A just-created payment can return `404` or partial history
  for up to the relay interval plus consumer lag.
- **Gaps in a payment's history cannot be detected,** because `version` is not contiguous.
- **The relay's transaction spans a Kafka send,** bounded by `max.block.ms` plus the producer delivery timeout, about 15 s (§5.2).
- **A database outage on history-service blocks the affected partitions** until it recovers, by
  design (§6.4).
- **The DLT has no consumer or replay tooling.** Records sent there need manual handling.
- **If Kafka is unreachable when payment-service starts,** topic creation is skipped after a short
  timeout, and the relay's sends fail until the topic exists (created by history-service at its
  start, or by a payment-service restart). Outbox rows are kept and published once it does.

---

## 11. Later milestones (unchanged scope)

| | Milestone |
|---|---|
| M4 | settlement-service: per-merchant payout batches from captured payments, consuming the same topic idempotently |
| M5 | Resilience4j around the acquirer; reconciliation of `UNKNOWN` and stuck `PENDING` payments |
| M6 | Micrometer/Prometheus, Grafana, correlation id across HTTP → outbox → Kafka → consumers |
| M7 | GateKeeper routing and rate limiting; tenant-bound machine clients |
| M8 | load test with committed scripts and results |
