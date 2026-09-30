# PayCore M0–M2 — Design

**Payment processing on the AuthCore / GateKeeper platform**
Date: 2026-09-27 · Status: approved in brainstorming, awaiting spec review · Revision 1

---

## 1. Purpose

PayCore is the payment-processing part of a platform that already has three services:

| Service | Role | Repo |
|---|---|---|
| **AuthCore** `:8080` | issuer — authenticates users, mints signed JWTs, publishes JWKS | `ProjectsCVs/authcore` |
| **GateKeeper** `:8081` | edge — routing, JWT/API-key authentication, coarse authorization | `ProjectsCVs/gatekeeper` |
| **ledger-service** `:8082` | resource — owns ledger data, trusts nothing upstream | `ProjectsCVs/ledger-service` |
| **PayCore** `:8083`, `:8091` | resource — authorizes, captures and voids card payments | `ProjectsCVs/paycore` (this document) |

This document covers **M0–M2 only**:

- **M0**: a multi-module Maven repository that builds and runs, with a secured payment-service and an acquirer simulator.
- **M1**: card payments. Authorize through the acquirer, then capture or void, driven by an explicit state machine persisted in Postgres.
- **M2**: idempotent payment creation. A client retry can neither create a second charge nor lose the first one.

Later milestones are listed in §13 and are out of scope here.

---

## 2. Responsibility matrix

| Concern | AuthCore | GateKeeper | PayCore payment-service | acquirer-simulator |
|---|---|---|---|---|
| Authenticate a human, issue tokens | **Owns** | never | never | never |
| Decide a user's permissions and tenant | **Owns** | never | never | never |
| Verify a JWT signature | — | does, at the edge | **does, independently** | never |
| Route traffic to PayCore | — | M7 | — | — |
| Rate limiting | — | M7 (GateKeeper M5) | — | — |
| Enforce authorization on payments | — | coarse, route-level (M7) | **Owns**, fine-grained | — |
| Own payment state and idempotency records | — | — | **Owns** | — |
| Decide approve / decline | — | — | never | **Owns** (simulated) |

**payment-service does not trust GateKeeper.** It verifies every token against AuthCore's JWKS itself and never authorizes on `X-GK-*` headers. Calling `:8083` directly, bypassing the gateway, is exactly as secure as calling it through the gateway. This is the same position ledger-service takes, for the same reasons.

**The services share a wire contract, not code.** The authorities converter and the JSON error shape are copied from ledger-service, not pulled in as a shared dependency.

---

## 3. Integration contract with AuthCore

| Seam | AuthCore side | PayCore side |
|---|---|---|
| Signature trust | `GET /oauth2/jwks` | `spring.security.oauth2.resourceserver.jwt.jwk-set-uri: http://localhost:8080/oauth2/jwks`; decoder built lazily, so PayCore starts without AuthCore running |
| Issuer | `iss` = `http://localhost:8080` | `spring.security.oauth2.resourceserver.jwt.issuer-uri: http://localhost:8080` alongside `jwk-set-uri`, as in ledger-service. With both set, Boot takes keys from `jwk-set-uri` (no discovery call at startup) and validates `iss` against `issuer-uri` |
| Claim vocabulary | `scope`, `tenant`, `roles`, `permissions` (the last three are **absent** on client-credentials tokens) | `AuthCoreAuthoritiesConverter` → `SCOPE_*`, `ROLE_*`, bare permissions |
| Payment permissions | `payments:read`, `payments:write` already seeded (DataSeeder) | see §3.1 |

### 3.1 Authorization rules

| Operation | Required authorities |
|---|---|
| Create, capture, void | `SCOPE_payments:write` **and** `payments:write` |
| Read one, list | `SCOPE_payments:read` **and** `payments:read` |

Both are required. The scope is what the *client application* was granted; the permission is what the *user* may do. A user holding `payments:write` who signs in through the `authcore-spa` client (granted `payments:read` only) must not be able to create a payment.

**These rules live in the `SecurityFilterChain`** (`authorizeHttpRequests`, matched on method and path), not in `@PreAuthorize`. Spring resolves and validates a controller's `@Valid @RequestBody` argument *before* a method-security interceptor runs, so with `@PreAuthorize` an unauthorized caller sending a bad body would get `400`. That would leak the validation rules to someone with no right to call the endpoint. In the filter chain, authentication and every authorization check, including the tenant check below, complete before the body is read. A test pins this: an unauthorized request with an invalid body gets `403`, not `400`.

### 3.2 The merchant is the tenant

A payment belongs to the `tenant` claim of the token that created it. The tenant is always taken from the verified token, never from the request.

- A token with no `tenant` claim is refused with `403` and `detail: "token carries no tenant"`. This is every client-credentials token today. See Known limitations (§12).
  - The check is an `AuthorizationManager` in the filter chain, composed with the scope and permission rules, so it runs before the body is read.
  - The two kinds of `403` carry different `detail` text: a missing scope or permission gives `"insufficient permission"`, and a missing tenant gives `"token carries no tenant"`. They are the same status but not the same failure. No amount of extra scope supplies a claim the token structurally lacks, and ledger-service learned this the hard way.
  - Scope and permission are checked first, so a token with neither gets `"insufficient permission"`.
- A payment belonging to another tenant is reported as `404`, not `403`, so the response does not confirm the payment exists.

---

## 4. Repository and runtime

```
paycore/
├─ pom.xml                      parent: Spring Boot 4.1.0 parent, Java 21, packaging pom
├─ docker-compose.yml           Postgres + Redis for local runs
├─ payment-service/             :8083   com.paycore.payment
└─ acquirer-simulator/          :8091   com.paycore.acquirer
```

- Each module builds its own executable jar and runs on its own. They share no code.
- `events`, `settlement-service` and `history-service` are **not** created in M0–M2. They arrive in M3/M4, when there is something to publish and consume.

**docker-compose.yml** (host ports chosen so it can run beside AuthCore's compose, which holds 5432, 6379, 9092 and 8090):

| Service | Image | Host port | Notes |
|---|---|---|---|
| postgres | `postgres:16-alpine` | `5433` | db `paycore_payments`, user/password `paycore` |
| redis | `redis:7-alpine` | `6380` | |

The two Spring applications run from Maven, not from compose, as the other platform services do.

**Stack (payment-service):** Web MVC (servlet), Spring Security OAuth2 resource server, Spring Data JPA, Flyway with the PostgreSQL module, Bean Validation, Spring Data Redis (Lettuce), Actuator. Test: Testcontainers (PostgreSQL, Redis), spring-security-test. Exact Boot 4 artifact coordinates are settled in the plan, checked against the published Boot 4.1 artifacts.

**Stack (acquirer-simulator):** Web MVC and Actuator only. In-memory storage.

**Why servlet and not reactive:** for the same reasons ledger-service gives. JPA and JDBC are blocking, the load profile doesn't need an event loop, and Java 21 virtual threads (`spring.threads.virtual.enabled: true`) cover the waiting on the acquirer.

---

## 5. Payments (M1)

### 5.1 API

| Endpoint | Success | Notes |
|---|---|---|
| `POST /payments` | `201` | Requires `Idempotency-Key` (§7). Body below. Response `status` is `AUTHORIZED` or `DECLINED` |
| `POST /payments/{id}/capture` | `200` | full capture only |
| `POST /payments/{id}/void` | `200` | |
| `GET /payments/{id}` | `200` | |
| `GET /payments?limit=N` | `200` | caller's tenant only, `created_at DESC, id DESC`, `limit` default 20, max 100; no cursor (keyset pagination is M7) |
| `GET /actuator/health` | `200` | unauthenticated |

**Create request:**

```json
{ "amount": 1999, "currency": "USD", "cardToken": "tok_approve", "description": "Order 42" }
```

| Field | Rule |
|---|---|
| `amount` | required integer, minor units, `1 ≤ amount ≤ 1_000_000_000` |
| `currency` | required, exactly one of `USD`, `EUR`, `GBP`, `EGP` (uppercase, no normalization) |
| `cardToken` | required, matches `tok_[a-z0-9_]{1,60}` |
| `description` | optional, at most 255 characters, no control characters (Unicode `Cc`, which includes NUL, tab and newline; Postgres refuses a NUL) |

Unknown JSON fields are rejected (`400`), so a misspelt field can't be silently ignored.

**Payment representation** (every success response and every idempotent replay):

```json
{
  "id": "5f0c…", "status": "AUTHORIZED", "amount": 1999, "currency": "USD",
  "description": "Order 42", "declineCode": null,
  "createdAt": "2026-09-27T10:15:30Z", "updatedAt": "2026-09-27T10:15:30Z"
}
```

`cardToken`, `tenant` and the acquirer's authorization id are not returned.

**Why a decline is `201`:** the payment resource was created and the card network gave a definite answer. A decline is a business outcome carried in `status` and `declineCode`, not an HTTP failure.

### 5.2 State machine

```
PENDING ──approved──▶ AUTHORIZED ──claim──▶ CAPTURING ──acquirer ok──▶ CAPTURED
   │                      │
   ├──declined──▶ DECLINED └──claim──▶ VOIDING ──acquirer ok──▶ VOIDED
   └──no answer──▶ UNKNOWN ──re-drive (§7.5)──▶ AUTHORIZED | DECLINED
```

`PaymentStatus` owns the transition table and is unit-tested without Spring. Any transition not drawn above is refused. A re-drive that again gets no answer leaves the payment `UNKNOWN` without a transition.

### 5.3 Transaction boundaries

**No DB transaction is held open across an acquirer call.**

**Authorize** (`POST /payments`, after the idempotency checks in §7):

1. **Tx 1:** insert the payment as `PENDING` and the idempotency row (§7.3). Commit.
2. Call the acquirer with the payment id as its `reference`.
3. **Tx 2:** set the outcome:
   - approved → `AUTHORIZED`
   - declined → `DECLINED` with `declineCode`
   - timeout, connection failure or `5xx` → `UNKNOWN`

If the process dies between steps 1 and 3, the `PENDING` row remains as evidence, and §7.5 re-drives it.

**Capture and void** claim the transition before calling out:

1. **Tx 1:** load the payment by `(id, tenant)`, check the transition, and set `CAPTURING` (or `VOIDING`) and `transition_started_at = now()`. JPA `@Version` enforces the claim: if two requests race, the loser's commit fails with an optimistic-lock conflict and it gets `409` **without calling the acquirer**.
2. Call the acquirer's capture/void for the stored authorization id.
3. **Tx 2:** set `CAPTURED` / `VOIDED`.

**Repeat and conflict rules for capture and void:**

| Current status | Capture | Void |
|---|---|---|
| `AUTHORIZED` | claim → `200 CAPTURED` | claim → `200 VOIDED` |
| `CAPTURED` | `200` current payment (already done) | `409` |
| `VOIDED` | `409` | `200` current payment (already done) |
| `CAPTURING`, started < 30 s ago | `409` "capture in progress", `Retry-After: 1` | `409` |
| `CAPTURING`, started ≥ 30 s ago | re-drive: call acquirer capture again, then `CAPTURED` | `409` |
| `VOIDING` | mirror image of `CAPTURING` | mirror image of `CAPTURING` |
| `PENDING`, `UNKNOWN`, `DECLINED` | `409` naming the current status | `409` naming the current status |

The re-drive is safe because the acquirer's capture and void are idempotent on the authorization id (§6). The 30 s window is `paycore.payments.transition-stale-after`.

If the acquirer fails during capture or void (timeout, connection failure, `5xx`), the payment stays in `CAPTURING`/`VOIDING` and the response is `502`, telling the caller to retry. It is never reverted to `AUTHORIZED`, because the capture may have happened.

### 5.4 The `UNKNOWN` outcome

When the acquirer does not give a definite answer, PayCore does not know whether the card was charged. It therefore **never records such a payment as failed**. The payment becomes `UNKNOWN`, and the response is:

```json
{ "error": "bad_gateway", "status": 502, "path": "/payments",
  "detail": "acquirer outcome unknown; retry with the same Idempotency-Key",
  "paymentId": "5f0c…" }
```

`paymentId` is the one addition to the platform error shape, and it appears only on this response. The client resolves the payment by retrying (§7.5). Clients that never retry are handled by M5's reconciliation job.

### 5.5 Acquirer client

- Spring `RestClient` over the JDK `HttpClient`.
- `paycore.acquirer.base-url` (default `http://localhost:8091`), `connect-timeout` 1 s, `read-timeout` 2 s.
- No retries, circuit breaker or bulkhead in M0–M2. Those are M5.
- `AcquirerClient` returns a sealed result (`Approved`, `Declined`, `NoAnswer`), so every caller must handle "no answer" explicitly. It never throws for timeout, connection failure or `5xx`.

---

## 6. acquirer-simulator contract

A stand-in for an external card network. It is **deliberately unauthenticated**: in production, this link would be a private, mutually authenticated connection outside the OAuth platform. The README says this.

| Endpoint | Behaviour |
|---|---|
| `POST /authorizations` `{reference, amount, currency, cardToken}` | `200 {authorizationId, reference, status: APPROVED\|DECLINED, declineCode}` |
| `POST /authorizations/{authorizationId}/capture` | `200 {authorizationId, status: CAPTURED}` |
| `POST /authorizations/{authorizationId}/void` | `200 {authorizationId, status: VOIDED}` |

**Deduplication on `reference`.** A second authorization with a reference the simulator has already seen returns the **stored** authorization and does not create a new one. The same reference with a different amount, currency or card token gets `409`. PayCore's safe re-drive (§7.5) depends on this.

**Capture and void** are idempotent on the authorization id:
- Repeating a completed capture or void returns `200` with the same result.
- Capturing a voided authorization, or voiding a captured one, gets `409`.
- An unknown id gets `404`.

**Behaviour by card token:**

| Token | Result |
|---|---|
| `tok_approve` | `APPROVED` |
| `tok_decline_funds` | `DECLINED`, `declineCode: "insufficient_funds"` |
| `tok_timeout` | **records an `APPROVED` authorization first**, then sleeps `simulator.slow-response` (default 5 s) before responding. The charge happens even though the caller times out, which is the case PayCore has to survive |
| `tok_error` | records nothing and returns `500` |
| any other `tok_…` | `APPROVED` |

`simulator.latency` (default `0ms`) adds a fixed delay to every response.

Because `tok_timeout` records the authorization before sleeping, a retry with the same reference finds it straight away and returns quickly.

---

## 7. Idempotency (M2)

### 7.1 Scope

- `POST /payments` **requires** `Idempotency-Key`: 1–255 characters, printable ASCII (`0x21–0x7E`). Missing or malformed → `400`.
- Capture and void do not take a key. They are idempotent through the state machine (§5.3).
- **A key is identified by `(tenant, key)`.** Two tenants sending `order-42` have two independent keys. Without this, a replay could return one tenant's payment to another.
- Checks run in this order: authentication → scope and permission → tenant present → body and header validation → idempotency. The first three happen in the filter chain (§3.1). An invalid request never touches an idempotency record.

### 7.2 Fingerprint

A SHA-256 hex digest of the canonical JSON of the validated request, with fields in the fixed order `amount`, `currency`, `cardToken`, `description`, and an absent `description` encoded as `null`.

The same key with a different fingerprint → `422` with `detail: "Idempotency-Key was already used with a different request"`. Postgres gives it whatever state the key's row is in, and so does a `COMPLETED` Redis entry. A Redis `IN_PROGRESS` lock answers `409` first, whatever its fingerprint: an in-flight attempt's fingerprint has not been checked against Postgres, so it is not grounds for a `422` (§7.4).

### 7.3 Storage

**Postgres is the guarantee.** Table `idempotency_keys`:

| Column | Type | Notes |
|---|---|---|
| `tenant` | `varchar(100)` | PK part 1 |
| `idempotency_key` | `varchar(255)` | PK part 2 |
| `fingerprint` | `char(64)` | |
| `payment_id` | `uuid` | FK → `payments(id)` |
| `state` | `varchar(16)` | `IN_PROGRESS` or `COMPLETED` (`CHECK` constraint) |
| `locked_until` | `timestamptz` | lease on the key while a request is running |
| `response_status` | `smallint` | set when `COMPLETED` |
| `response_body` | `jsonb` | set when `COMPLETED` |
| `created_at` | `timestamptz` | |

The row is inserted in the **same transaction** as the `PENDING` payment (§5.3 Tx 1), so the primary key alone prevents two payments for one key, whatever Redis says.

**Redis is the fast path.** Key `paycore:idem:{len(tenant)}:{tenant}:{key}`, value JSON `{state, fingerprint, owner, responseStatus?, responseBody?}`. The tenant is length-prefixed so that tenant `a:b` with key `c` and tenant `a` with key `b:c` can't share a Redis key, which would let one tenant replay another's payment.

- **Lock:** `SET … NX PX 30000` with `state: IN_PROGRESS` and a random `owner` token.
- **Completed:** overwritten with `state: COMPLETED` and the response, with `EX` of 24 h.
- **Release:** a Lua compare-and-delete on `owner`, so a request whose lock has expired can never delete another request's lock.
- Lettuce command timeout is 200 ms, so an unreachable Redis costs at most that per call.

**Redis fails open.** Any Redis failure is logged at `WARN` and treated as a cache miss / lock acquired, and the request continues on the Postgres path. That includes an entry that can't be read or is incomplete, which is also deleted, and a failure after Postgres has recorded the outcome, which never changes the response. This is safe **only** because Postgres, not Redis, enforces the guarantee. A comment in `IdempotencyCache` and a paragraph in the README both say so.

Settings: `paycore.idempotency.lock-ttl` (30 s, must exceed the acquirer read timeout by a wide margin) and `paycore.idempotency.response-ttl` (24 h, Redis only).

### 7.4 Request flow

```
validate → fingerprint F
│
├─ Redis GET  ──COMPLETED──▶ F matches? replay stored response  : 422
│             ──IN_PROGRESS─▶ 409 in progress (whatever the fingerprint: an in-flight attempt's fingerprint is not authoritative)
│             ──miss / Redis down / entry unreadable (deleted)──▼
├─ Redis SET NX lock ──not acquired──▶ re-read, as above
│                    ──acquired / Redis down──▼
├─ DB Tx 1: INSERT payment(PENDING) + idempotency_keys(IN_PROGRESS, locked_until = now + lock-ttl)
│    └─ PK violation ──▶ read the row:
│          fingerprint differs          → 422
│          COMPLETED                    → replay from DB, repopulate Redis
│          IN_PROGRESS, lease live      → 409 in progress
│          IN_PROGRESS, lease expired   → claim and re-drive (§7.5)
├─ acquirer call
└─ DB Tx 2: payment outcome, and:
      AUTHORIZED / DECLINED → idempotency row COMPLETED + response snapshot; Redis COMPLETED
      UNKNOWN               → idempotency row stays IN_PROGRESS, locked_until = now(); Redis lock released
```

- A replay returns the stored status and body with the header `Idempotent-Replayed: true`.
- "409 in progress" carries `Retry-After: 1`.

### 7.5 Re-drive: retrying without charging twice

An `IN_PROGRESS` row whose lease has expired belongs to one of two cases:
- a request that got `UNKNOWN`, whose lease was released on purpose, or
- a request whose process died and left the payment `PENDING`.

A retry with the same key and fingerprint **claims** the row:

```sql
UPDATE idempotency_keys SET locked_until = now() + :ttl
WHERE tenant = :tenant AND idempotency_key = :key
  AND state = 'IN_PROGRESS' AND locked_until <= now()
```

The claim succeeds only if exactly one row is updated. Exactly one of any number of concurrent retries wins; the others get `409`.

The winner calls the acquirer again for the **same payment**, using the same `reference`. Because the acquirer deduplicates on `reference` (§6), a charge that went through on the first attempt comes back as that same authorization, and no second one is created. The outcome is then recorded exactly as in §7.4, Tx 2. If the re-drive also gets no answer, the payment stays `UNKNOWN` and the key is released again.

**A `502` is never stored as a replayable response**, because replaying it would freeze the payment in `UNKNOWN` for good.

### 7.6 What a replay returns

A replay returns the payment **as it was when the key completed**. It is a snapshot, not the current state: if the payment has since been captured, the replay still shows `AUTHORIZED`. Stripe behaves the same way. `GET /payments/{id}` gives the current state.

---

## 8. Storage schema

Flyway migrations live in `payment-service/src/main/resources/db/migration`, and `spring.jpa.hibernate.ddl-auto: validate`.

**`V1__create_payments.sql`**

| Column | Type | Constraint |
|---|---|---|
| `id` | `uuid` | PK, generated by the application |
| `tenant` | `varchar(100)` | not null |
| `amount_minor` | `bigint` | not null, `CHECK (amount_minor BETWEEN 1 AND 1000000000)` |
| `currency` | `char(3)` | not null, `CHECK (currency IN ('USD','EUR','GBP','EGP'))` |
| `card_token` | `varchar(64)` | not null |
| `description` | `varchar(255)` | nullable |
| `status` | `varchar(16)` | not null, `CHECK` over the eight statuses in §5.2 |
| `decline_code` | `varchar(64)` | nullable |
| `acquirer_authorization_id` | `varchar(64)` | nullable |
| `transition_started_at` | `timestamptz` | nullable |
| `version` | `bigint` | not null, JPA `@Version` |
| `created_at`, `updated_at` | `timestamptz` | not null |

Index: `(tenant, created_at DESC, id DESC)` for the list query.

**`V2__create_idempotency_keys.sql`** — the table in §7.3.

---

## 9. Error handling

Every refusal and failure is rendered in the platform shape `{error, status, path, detail?}`, identical to ledger-service and GateKeeper.

- `detail` is always text PayCore wrote itself. It is never a framework or driver exception message.
- An unexpected exception is `500` with no `detail`, and the stack trace is logged.

| Status | Cause |
|---|---|
| `400` | malformed JSON, unknown field, failed validation (`detail` names each field and rule), missing or malformed `Idempotency-Key`, `limit` out of range |
| `401` | no token, invalid or expired token (JSON entry point, `WWW-Authenticate` as Spring sets it) |
| `403` | missing scope or permission; `"token carries no tenant"`. No `WWW-Authenticate` header on a `403` |
| `404` | no such payment **for this tenant** |
| `409` | invalid transition (`detail` names the current status); lost optimistic-lock race; idempotency key or transition in progress (`Retry-After: 1`) |
| `422` | idempotency key reused with a different request |
| `502` | acquirer gave no definite answer (with `paymentId` on authorize, §5.4), or refused a capture or void (`"acquirer refused the capture; the payment needs reconciliation"`), or refused the authorization (`"acquirer refused the authorization; the payment needs reconciliation"`, with `paymentId`) |

---

## 10. Testing

Integration tests use **Testcontainers** Postgres and Redis. In payment-service tests, the acquirer is a **`FakeAcquirer`** test fixture built on the JDK `com.sun.net.httpserver.HttpServer`. It implements the §6 contract, lets each test program delays and failures, and counts calls per endpoint and per reference. It adds no dependency that could clash with Boot 4. JWTs come from spring-security-test's `jwt()` post-processor.

The following tests are required. Each one pins a promise the design makes:

| Promise | Test |
|---|---|
| No double charge under concurrency | 20 concurrent `POST /payments` with the same key and body → exactly 1 payment row, exactly 1 acquirer authorization call; every response is `201` (winner or replay) or `409` |
| Redis is not the guard | Flush Redis between two identical requests → 1 payment. Redis pointed at a closed port → requests succeed, still 1 payment for repeated keys |
| Tenant-scoped keys | Tenants A and B send the same key → 2 payments |
| Tenant isolation | Tenant B `GET`/capture/void on A's payment → `404` |
| Key misuse | Same key, different amount → `422`, with no acquirer call |
| Replay | Second identical request → same body, `Idempotent-Replayed: true`, no acquirer call |
| No lost charge | Acquirer times out → `UNKNOWN` + `502` with `paymentId`; retry with same key → same `paymentId`, acquirer sees the same `reference`, one authorization, final `201 AUTHORIZED` |
| Crash recovery | Seed a `PENDING` payment with an expired `IN_PROGRESS` key → retry re-drives it to a final state |
| Capture/void race | Concurrent capture and void → one `200`, one `409`, one acquirer call |
| Stale transition | `CAPTURING` older than the window → capture retry reaches `CAPTURED` |
| Trust boundary | No token → `401`; scope without permission → `403`; permission without scope → `403`; no `tenant` claim → `403` with its own `detail`, distinct from `"insufficient permission"`; neither `403` carries `WWW-Authenticate` |
| Authorization before parsing | Unauthorized token with an invalid body → `403`, not `400`; the same for a token with no tenant |
| Validation | Each field rule in §5.1 → `400` naming the field |
| Schema | Context starts with Flyway plus `ddl-auto: validate` against real Postgres |

**Unit tests:** the `PaymentStatus` transition table (every allowed and refused pair), and the fingerprint (same input gives the same digest; field order and a `null` description are handled).

**acquirer-simulator tests:** deduplication on `reference`, `409` on a mismatched reuse, each card token's behaviour, and capture/void idempotency and conflicts.

---

## 11. Delivery

- Repository `ProjectsCVs/paycore`, branch `master`. One `feature/task-N` branch per plan task, merged back, as in the other platform repos.
- Commit identity is the repository-local `Ezzat Mohamed <ezat71101@gmail.com>`, matching the other platform repos.
- `README.md` follows ledger-service's structure: the platform table, running it, a curl walkthrough (get a token from AuthCore → create → replay → capture), endpoints, idempotency explained, Known limitations, roadmap.
- Creating the GitHub remote and pushing are done by the user, or on explicit request.
- The CV is not edited until M8.

---

## 12. Known limitations (to be stated in the README)

- **Client-credentials tokens can't create payments.** They carry no `tenant` claim. Real merchants integrate server-to-server, so this is the most significant gap. M7 adds a tenant-bound machine client to AuthCore.
- **`UNKNOWN` payments resolve only when the client retries.** A client that never retries leaves the payment `UNKNOWN`. M5 adds reconciliation against the acquirer.
- **No resilience around the acquirer** beyond timeouts: no retry, circuit breaker or bulkhead (M5).
- **A capture or void the acquirer refuses (4xx) leaves the payment `CAPTURING` or `VOIDING`.** A retry within the 30 s window gets `409` "capture in progress" (or "void in progress"); every stale retry gets `502` "needs reconciliation" again, until M5's reconciliation resolves it against the acquirer; a restarted in-memory simulator is the realistic way to hit this locally.
- **An authorization the acquirer refuses (4xx) leaves the payment's status unchanged**: `PENDING` on a first attempt, `UNKNOWN` if it is a re-drive of a payment that earlier got no answer. It is never moved to `UNKNOWN` for the refusal, which promises that a retry resolves it: here none will. The response is `502` "needs reconciliation" with `paymentId`, and the key's lease is released, so every retry with the same key re-drives and gets that same `502`, until M5's reconciliation resolves it against the acquirer.
- **The Postgres lease has no owner fence.** A request that stalls past its lease (30 s, far above the acquirer's timeouts) can release or complete a key a later request has since claimed. The cost is at most extra acquirer calls and a `502`, never a double charge: every attempt uses the same payment id as the acquirer's `reference`, and the acquirer deduplicates on it.
- **A stale Redis lock delays a re-drive or a replay by up to lock-ttl.** A Redis `IN_PROGRESS` lock left behind by a crashed request, or by a release or completion that never reached Redis, answers every retry with a retryable `409` until it expires, up to lock-ttl (30 s), even where Postgres would already allow a re-drive (after a lost release) or replay the stored response (after a lost completion). A release or completion is lost when its command times out, for example during a Redis pause, or when a release's `EVALSHA` times out and then runs late against a Redis that restarted with an empty script cache. A plain `NOSCRIPT` is not a problem: the client falls back to `EVAL` and the release goes through. It never causes a double charge or a wrong answer; the retry succeeds once the lock lapses.
- **Idempotency rows are never purged.** Redis entries expire after 24 h, but the Postgres rows remain and keep replaying.
- **A replay is a snapshot** (§7.6), not the payment's current state.
- **Full capture only.** No partial captures, refunds or multi-capture.
- **Not behind GateKeeper yet.** No route and no rate limiting until M7.
- **Offset-free, cursor-free listing.** `limit` only, newest first (keyset pagination in M7).
- **The acquirer link is unauthenticated** by design (§6).
- **Audience is not validated.** AuthCore sets `aud` to the client id, not the resource, so scope + permission act as the audience check, as in ledger-service. A token carrying `payments:*` that was presented to another platform service can be replayed here.
- **Access tokens typed `at+jwt` (RFC 9068) are rejected** by Security 7's default `JwtTypeValidator`. AuthCore sets no `typ` today. If it ever adopts RFC 9068, this service needs a type validator that accepts `at+jwt`.
- **An unknown `kid` re-fetches AuthCore's JWKS on every request.** Spring's default JWK source does not rate-limit refreshes, so a forged token with a made-up `kid` costs one call to AuthCore per request. ledger-service has the same default. Rate-limiting JWKS refresh belongs with GateKeeper's edge protection (M7).

---

## 13. Later milestones (out of scope)

| | Milestone |
|---|---|
| M3 | Transactional outbox, polling relay (`FOR UPDATE SKIP LOCKED`), Kafka keyed by merchant; history-service consuming through an inbox table |
| M4 | settlement-service: per-merchant payout batches from captured payments |
| M5 | Resilience4j around the acquirer; reconciliation job for `UNKNOWN` payments |
| M6 | Micrometer/Prometheus, Grafana, correlation ID across HTTP → outbox → Kafka → consumers |
| M7 | GateKeeper routes and rate limiting, AuthCore tenant-bound machine client, Redis cache and keyset pagination on reads |
| M8 | k6 load test with committed scripts and results; README; CV rewritten against measured results |
