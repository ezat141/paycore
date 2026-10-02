# PayCore

Card payment processing for the AuthCore / GateKeeper platform: authorize, capture and void, with **idempotent payment creation. A client retry can neither charge a card twice nor lose a charge that already happened.**

The hard case is the timeout. The acquirer charges the card, then answers too late; the client sees an error and retries. PayCore never records that payment as failed. It becomes `UNKNOWN`, and the retry, sent with the same `Idempotency-Key`, re-drives the *same* payment under the *same* acquirer reference. The acquirer recognises the reference and returns the charge that already happened. One payment, one authorization. [Steps 4 and 5 of the walkthrough](#4-the-timeout) show it on a live run: the `paymentId` in the `502` comes back as the `id` of an `AUTHORIZED` payment.

**Stack:** Java 21 · Spring Boot 4.1 · Spring Security OAuth2 Resource Server · Spring Data JPA · Flyway · PostgreSQL 16 · Redis 7 · Testcontainers

**Scope:** milestones M0–M2: a multi-module repo with a secured payment-service and an acquirer simulator, authorize/capture/void on an explicit state machine, and idempotent creation. Everything after that is in the [Roadmap](#roadmap), and [Known limitations](#known-limitations) says where the line is.

---

## Contents

- [Platform](#platform)
- [Running it](#running-it)
- [Endpoints](#endpoints)
- [Walkthrough](#walkthrough)
- [How idempotency works](#how-idempotency-works)
- [When the acquirer does not say yes or no](#when-the-acquirer-does-not-say-yes-or-no)
- [Capture and void](#capture-and-void)
- [Testing](#testing)
- [Known limitations](#known-limitations)
- [Roadmap](#roadmap)

---

## Platform

| Service | Owns |
|---|---|
| [**AuthCore**](https://github.com/ezat141/authcore) `:8080` | Authenticating users, issuing and signing JWTs, publishing JWKS |
| [**GateKeeper**](https://github.com/ezat141/gatekeeper) `:8081` | Routing, edge rejection, identity propagation |
| [**ledger-service**](https://github.com/ezat141/ledger-service) `:8082` | Ledger data |
| **PayCore payment-service** `:8083` (this repo) | Payments, idempotency keys, fine-grained authorization over both |
| **PayCore acquirer-simulator** `:8091` (this repo) | A stand-in card network |

The two PayCore applications share no code; each builds its own jar.

payment-service verifies AuthCore's JWTs itself, against the published JWKS, and never authorizes on GateKeeper's `X-GK-*` headers. Calling `:8083` directly is exactly as secure as calling it through the gateway. It is not routed through GateKeeper yet (M7).

**The merchant is the tenant.** A payment belongs to the `tenant` claim of the token that created it; the tenant is never read from the request. Another tenant's payment is a `404` `"no such payment"`, not a `403`, so the response does not confirm it exists.

**Every authorization rule lives in the security filter chain, not in `@PreAuthorize`.** Spring MVC validates a `@Valid` body *before* method security runs, so with annotations an unauthorized caller could get a `400` and learn the validation rules. Here the chain checks, in order:

1. the client's scope **and** the user's permission (`SCOPE_payments:write` and `payments:write`, say). The scope is what the client application was granted, the permission is what the user may do, and both are required. A `permissions` entry named like `SCOPE_…` or `ROLE_…` is ignored, so it cannot stand in for either;
2. then a tenant: the `tenant` claim must be a non-blank string. A list or a blank string counts as no tenant.

All of it runs before the body is read. A missing scope or permission is `403` `"insufficient permission"`; a missing tenant is `403` `"token carries no tenant"`, a separate message because no amount of extra scope supplies a claim the token lacks. A route with no rule is `403` `"no such operation"`, even with a full token. Only `/actuator/health` is public.

---

## Running it

Requires Java 21 and Docker.

```bash
docker compose up -d                              # Postgres :5433, Redis :6380
./mvnw -pl acquirer-simulator spring-boot:run     # :8091   (.\mvnw.cmd in PowerShell)
./mvnw -pl payment-service spring-boot:run        # :8083
```

The host ports avoid those of AuthCore's compose, so both run side by side. payment-service starts without AuthCore running: the JWT decoder fetches keys on the first request that needs a signature checked, never at startup. Redis is optional too. Its health indicator is disabled, so a missing Redis does not mark the service `DOWN`, and idempotency [falls back to Postgres](#how-idempotency-works).

The acquirer simulator is deliberately unauthenticated. In production that link is a private, mutually authenticated connection outside the OAuth platform.

### Getting a token

Start AuthCore as its README describes. You need a user token for `alice` in tenant `acme`: she holds `ROLE_ADMIN`, which carries `payments:write`, and the confidential `authcore-client` is granted `payments:read payments:write`. Her password is in [AuthCore's seed table](https://github.com/ezat141/authcore#seeded-clients-and-users).

AuthCore requires PKCE on every authorization-code request, confidential clients included. Without a `code_challenge` the authorize endpoint refuses with `invalid_request`. The pair below is AuthCore's documented test pair. Open this URL in a **private window**, so a session from another tenant isn't reused, log in as `alice` and approve both scopes:

```
http://localhost:8080/oauth2/authorize?response_type=code&client_id=authcore-client&redirect_uri=http://127.0.0.1:8080/authorized&scope=openid%20payments:read%20payments:write&tenant=acme&code_challenge=jOE5exqeFE_I-Pd0J6sXoCuGr4fI1i-07DziFgujcnQ&code_challenge_method=S256
```

Exchange the code that `/authorized` displays:

```bash
curl -s -u authcore-client:secret http://localhost:8080/oauth2/token \
  -d grant_type=authorization_code -d code=PASTE_CODE \
  -d redirect_uri=http://127.0.0.1:8080/authorized \
  -d code_verifier=authcore-test-code-verifier-minimum-43-chars-00
```

Put the `access_token` in `TOKEN`. Decoded, the live run's token carried:

```json
{
  "sub": "alice",
  "aud": "authcore-client",
  "iss": "http://localhost:8080",
  "tenant": "acme",
  "scope": ["payments:write", "openid", "payments:read"],
  "roles": ["ADMIN"],
  "permissions": ["payments:write", "keys:rotate", "clients:rotate-secret", "accounts:read",
                  "keys:read", "payments:read", "accounts:read:all"]
}
```

It lives 300 seconds (`"expires_in": 300`), so run the walkthrough promptly. Use `localhost`, not `127.0.0.1`, for AuthCore's own URLs: payment-service pins the issuer to `http://localhost:8080`, and a token obtained through `127.0.0.1` carries a different `iss` and gets a `401`. [GateKeeper's README](https://github.com/ezat141/gatekeeper#issuer-pinning-and-the-trap-it-exists-to-catch) explains the trap.

---

## Endpoints

| Endpoint | Requires | Result |
|---|---|---|
| `POST /payments` + `Idempotency-Key` | `payments:write` (scope and permission) and a tenant | `201` `AUTHORIZED` or `DECLINED`; `502` + `paymentId` when the acquirer gives no definite answer |
| `POST /payments/{id}/capture` | `payments:write` and a tenant | `200` `CAPTURED` |
| `POST /payments/{id}/void` | `payments:write` and a tenant | `200` `VOIDED` |
| `GET /payments/{id}` | `payments:read` and a tenant | the payment, within your tenant |
| `GET /payments?limit=N` | `payments:read` and a tenant | newest first, `1 ≤ limit ≤ 100`, default 20 |
| `GET /actuator/health` | none | `UP` |

**The create body.** `{"amount": 1999, "currency": "USD", "cardToken": "tok_approve", "description": "Order 1001"}`

| Field | Rule |
|---|---|
| `amount` | required integer in minor units (`1999` is 19.99), `1 ≤ amount ≤ 1000000000`. `19.99` and `"1999"` are both `400`, never truncated or coerced |
| `currency` | required, exactly `USD`, `EUR`, `GBP` or `EGP` |
| `cardToken` | required, `tok_` plus 1–60 of `[a-z0-9_]`. Card data never reaches PayCore |
| `description` | optional, at most 255 characters, no control characters (NUL, tab and newline included) |

An unknown field is `400` `"unknown field: <name>"`, so a misspelt field can't be silently ignored. A failed rule is `400` naming the field, for example `"amount: must be at least 1"`. `Idempotency-Key` is required: 1–255 printable ASCII characters, no spaces.

**The simulator's card tokens.** `tok_approve` approves. `tok_decline_funds` declines with `insufficient_funds`. `tok_timeout` records an approval and then stalls for 5 s, well past payment-service's 2 s read timeout, so the card is charged and the caller never hears it. `tok_error` answers `500` and records nothing. Any other `tok_…` approves.

A decline is `201`: the payment exists and the network gave a definite answer, carried in `status` and `declineCode`.

Every mapping produces `application/json`. A request whose `Accept` excludes JSON is `406` before its handler runs, so a create never reaches the acquirer. Every error has the platform's shape, `{"error", "status", "path", "detail"}`, and is always rendered as JSON. `detail` is always text PayCore wrote, never an exception's message.

| Status | When | `detail` |
|---|---|---|
| `400` | malformed or invalid body, bad header, bad `limit` | `"malformed request body"`, `"unknown field: …"`, `"<field>: <rule>"`, `"Idempotency-Key header is required"`, `"limit must be between 1 and 100"`, … |
| `401` | no token, or one that fails verification. `WWW-Authenticate: Bearer` | none |
| `403` | see [Platform](#platform). No `WWW-Authenticate` | `"insufficient permission"`, `"token carries no tenant"`, `"no such operation"` |
| `404` | no such payment **in your tenant** | `"no such payment"` |
| `406` | `Accept` excludes `application/json` | none |
| `409` | key or transition in progress (`Retry-After: 1`), invalid transition, lost race | `"a request with this Idempotency-Key is in progress"`, `"capture in progress"`, `"cannot void a payment that is CAPTURED"`, `"payment was modified concurrently; retry"` |
| `422` | key reused with a different request | `"Idempotency-Key was already used with a different request"` |
| `502` | the acquirer gave no definite answer, or refused | [see below](#when-the-acquirer-does-not-say-yes-or-no) |

---

## Walkthrough

Run against AuthCore on `:8080`, the simulator on `:8091` and payment-service on `:8083`, with alice@acme's token. Every response below is the real one from that run, ids and timestamps included. The bodies are pretty-printed. Step 1 shows every header; after that only the status line and the headers that matter are shown.

### 1. Create a payment

```bash
curl -si http://localhost:8083/payments -H "Authorization: Bearer $TOKEN" \
  -H "Idempotency-Key: order-1001" -H "Content-Type: application/json" \
  -d '{"amount":1999,"currency":"USD","cardToken":"tok_approve","description":"Order 1001"}'
```

```
HTTP/1.1 201
X-Content-Type-Options: nosniff
X-XSS-Protection: 0
Cache-Control: no-cache, no-store, max-age=0, must-revalidate
Pragma: no-cache
Expires: 0
X-Frame-Options: DENY
Content-Type: application/json
Transfer-Encoding: chunked
Date: Fri, 02 Oct 2026 15:36:12 GMT
```
```json
{
  "id": "2eb82e0d-5a5a-4515-a949-f4cf2806420b",
  "status": "AUTHORIZED",
  "amount": 1999,
  "currency": "USD",
  "description": "Order 1001",
  "declineCode": null,
  "createdAt": "2026-10-02T15:36:12.192788Z",
  "updatedAt": "2026-10-02T15:36:12.422874Z"
}
```

`cardToken`, the tenant and the acquirer's authorization id are never returned.

### 2. Retry it

The same command again:

```
HTTP/1.1 201
Idempotent-Replayed: true
```
```json
{
  "id": "2eb82e0d-5a5a-4515-a949-f4cf2806420b",
  "status": "AUTHORIZED",
  "amount": 1999,
  "currency": "USD",
  "description": "Order 1001",
  "declineCode": null,
  "createdAt": "2026-10-02T15:36:12.192788Z",
  "updatedAt": "2026-10-02T15:36:12.422874Z"
}
```

The same body, byte for byte, and `Idempotent-Replayed: true`. No second charge.

### 3. Reuse the key for a different payment

The same key, with `"amount":2500`:

```
HTTP/1.1 422
```
```json
{
  "error": "unprocessable_content",
  "status": 422,
  "path": "/payments",
  "detail": "Idempotency-Key was already used with a different request"
}
```

### 4. The timeout

```bash
curl -si http://localhost:8083/payments -H "Authorization: Bearer $TOKEN" \
  -H "Idempotency-Key: order-1002" -H "Content-Type: application/json" \
  -d '{"amount":1999,"currency":"USD","cardToken":"tok_timeout"}'
```

```
HTTP/1.1 502
```
```json
{
  "error": "bad_gateway",
  "status": 502,
  "path": "/payments",
  "detail": "acquirer outcome unknown; retry with the same Idempotency-Key",
  "paymentId": "b8cfac3f-e559-4c9e-838a-561a20e2c9b2"
}
```

The simulator approved this charge, then stalled past the read timeout. PayCore can't know whether the card was charged, so the payment is now `UNKNOWN`, not failed, and the client is told its id.

### 5. Retry the timeout

The same command again:

```
HTTP/1.1 201
```
```json
{
  "id": "b8cfac3f-e559-4c9e-838a-561a20e2c9b2",
  "status": "AUTHORIZED",
  "amount": 1999,
  "currency": "USD",
  "description": null,
  "declineCode": null,
  "createdAt": "2026-10-02T15:36:12.790991Z",
  "updatedAt": "2026-10-02T15:36:14.973305Z"
}
```

**The `id` is step 4's `paymentId`.** The retry re-drove that payment under the same reference; the simulator found the authorization it had already recorded and returned it straight away. The retry recovered the original charge and made no new one. There is no `Idempotent-Replayed` header: this is a fresh answer, not a replay of the `502`, which is never stored.

### 6. Capture it, twice

```bash
curl -si -X POST http://localhost:8083/payments/b8cfac3f-e559-4c9e-838a-561a20e2c9b2/capture \
  -H "Authorization: Bearer $TOKEN"
```

```
HTTP/1.1 200
```
```json
{
  "id": "b8cfac3f-e559-4c9e-838a-561a20e2c9b2",
  "status": "CAPTURED",
  "amount": 1999,
  "currency": "USD",
  "description": null,
  "declineCode": null,
  "createdAt": "2026-10-02T15:36:12.790991Z",
  "updatedAt": "2026-10-02T15:36:15.579099Z"
}
```

The same command again returns `HTTP/1.1 200` and the identical body, `updatedAt` included. A completed capture is answered from the database without calling the acquirer again.

### 7. List

```bash
curl -si "http://localhost:8083/payments?limit=5" -H "Authorization: Bearer $TOKEN"
```

```
HTTP/1.1 200
```
```json
[
  {
    "id": "b8cfac3f-e559-4c9e-838a-561a20e2c9b2",
    "status": "CAPTURED",
    "amount": 1999,
    "currency": "USD",
    "description": null,
    "declineCode": null,
    "createdAt": "2026-10-02T15:36:12.790991Z",
    "updatedAt": "2026-10-02T15:36:15.579099Z"
  },
  {
    "id": "2eb82e0d-5a5a-4515-a949-f4cf2806420b",
    "status": "AUTHORIZED",
    "amount": 1999,
    "currency": "USD",
    "description": "Order 1001",
    "declineCode": null,
    "createdAt": "2026-10-02T15:36:12.192788Z",
    "updatedAt": "2026-10-02T15:36:12.422874Z"
  }
]
```

Newest first. Two payments from five create requests: the replay, the refused reuse and the timeout's retry made none of their own.

### 8. No token

```bash
curl -si http://localhost:8083/payments
```

```
HTTP/1.1 401
WWW-Authenticate: Bearer
Content-Type: application/json;charset=UTF-8
```
```json
{"error": "unauthorized", "status": 401, "path": "/payments"}
```

### 9. A decline

```bash
curl -si http://localhost:8083/payments -H "Authorization: Bearer $TOKEN" -H "Idempotency-Key: order-1003" -H "Content-Type: application/json" -d '{"amount":1999,"currency":"USD","cardToken":"tok_decline_funds"}'
```

```
HTTP/1.1 201
```
```json
{
  "id": "e4b2c594-1ff5-4b21-abad-658dd38b32e1",
  "status": "DECLINED",
  "amount": 1999,
  "currency": "USD",
  "description": null,
  "declineCode": "insufficient_funds",
  "createdAt": "2026-10-02T15:36:16.020731Z",
  "updatedAt": "2026-10-02T15:36:16.034542Z"
}
```

---

## How idempotency works

A key is identified by `(tenant, key)`, so two merchants both sending `order-42` have two independent keys. A request's fingerprint is a SHA-256 of its validated body in a fixed canonical form. The checks run in order: authentication, scope and permission, tenant, then body and header validation, then idempotency. An invalid request never touches an idempotency record.

**Two layers, one guarantee.**

| Layer | Role | If it is missing |
|---|---|---|
| Postgres `idempotency_keys`, primary key `(tenant, idempotency_key)` | **The guarantee.** The key row is inserted in the same transaction as the `PENDING` payment, so a duplicate cannot get past the primary key | — |
| Redis `paycore:idem:{len(tenant)}:{tenant}:{key}` | **Fast path.** A `SET NX` lock turns concurrent duplicates away, and a cached completed response answers replays, both without a Postgres round trip | Fails open to Postgres |

The tenant is length-prefixed in the Redis key, so tenant `a:b` with key `c` and tenant `a` with key `b:c` can't share an entry.

**Redis fails open on any failure**: unreachable, slow (a 200 ms command timeout in production), an entry it can't parse, an entry that makes no sense, or a fault after Postgres has already recorded the outcome. Each is logged at `WARN` and treated as a miss or an acquired lock, and the request carries on against Postgres. Failing open is safe *only* because Redis is not the guard. `RedisDownTest` points Redis at a port nothing listens on and still sees one payment and one acquirer call under ten concurrent retries. `RedisPausedTest` pauses a real Redis container, so connections are accepted and never answered, and payments are still created and replayed within the timeouts.

What Redis may say is deliberately limited:

- **Only a completed entry may answer `422`.** It was checked against Postgres before it was written. An in-flight `IN_PROGRESS` lock answers `409` `"a request with this Idempotency-Key is in progress"` with `Retry-After: 1`, whatever its fingerprint, because an in-flight attempt's fingerprint was never checked and a `422` on its word could refuse a key that Postgres would replay.
- **An unusable entry is deleted**: unreadable JSON, a missing fingerprint, or a completed body that won't replay. The delete is a compare-and-delete that removes only the value that was read, never one that has replaced it since. The request then falls through to Postgres, which replays its own snapshot and repopulates Redis.
- **The lock is released by a compare-and-delete on its owner token**, so a request whose lock expired can't delete a newer request's lock.
- **The cache uses its own JSON mapper**, lenient on unknown fields, so an entry written by a newer release still replays during a rolling deploy. A replay from Postgres is lenient in the same way: a snapshot holding a field the response no longer has still replays.

**The re-drive.** A `502` is never stored as a key's answer: replaying it would freeze the payment in `UNKNOWN` for good. Instead the key stays `IN_PROGRESS` and its lease is released. The next retry with the same key and body claims it:

```sql
UPDATE idempotency_keys SET locked_until = now() + make_interval(secs => :leaseSeconds)
WHERE tenant = :tenant AND idempotency_key = :key
  AND state = 'IN_PROGRESS' AND locked_until <= now()
```

Exactly one concurrent retry updates the row, and the rest get `409`. The winner calls the acquirer again with the same payment id as its reference. A payment left `PENDING` by a crashed process is re-driven the same way once its 30-second lease lapses.

**A replay is a snapshot.** It returns the response as it was when the key completed, as Stripe does. If the payment has since been captured, the replay still says `AUTHORIZED`; `GET /payments/{id}` gives the current state.

---

## When the acquirer does not say yes or no

`AcquirerClient` returns a sealed outcome (`Approved`, `Declined`, `NoAnswer`), so no caller can forget the third case. It makes one attempt, bounded by a 1 s connect timeout and a 2 s read timeout, with no retries yet (M5).

| What happened | On authorize | On capture or void |
|---|---|---|
| **No definite answer**: a timeout at any point in the exchange (after the headers arrive, too), a refused connection, a `5xx`, or an unreadable body. On authorize, a missing or unrecognised `status` as well | Payment → `UNKNOWN`. `502` `"acquirer outcome unknown; retry with the same Idempotency-Key"` + `paymentId`. Lease released, so a retry re-drives | Payment stays `CAPTURING`/`VOIDING`, never reverted, since it may have happened. `502` `"acquirer outcome unknown; retry the capture"` (or `void`) |
| **The acquirer refused** (`4xx`) | Status left unchanged (`PENDING`, or `UNKNOWN` on a re-drive). `502` `"acquirer refused the authorization; the payment needs reconciliation"` + `paymentId`. Lease released | Payment stays `CAPTURING`/`VOIDING`. `502` `"acquirer refused the capture; the payment needs reconciliation"` (or `void`) |
| **It answered, but recording the answer failed** | `502` `"acquirer outcome unknown; retry with the same Idempotency-Key"` + `paymentId`. The payment keeps its status, and a retry re-drives it under the same reference | — |

A refusal is not `UNKNOWN`. `UNKNOWN` promises that a retry resolves the payment, and after a refusal none will: the acquirer's record and PayCore's disagree, and only reconciliation (M5) can settle that. [Known limitations](#known-limitations) has the consequences.

---

## Capture and void

Capture and void **claim** the transition in the database before calling out: `AUTHORIZED → CAPTURING` (or `VOIDING`), guarded by JPA `@Version`. When a capture and a void race and both load the payment at the same version, the loser's commit fails and it gets `409` `"payment was modified concurrently; retry"`. When one loads after the other has committed, it sees the in-flight status and gets `409` naming it. Either way the loser **never reaches the acquirer**, so PayCore's state and the acquirer's cannot diverge.

No transaction is ever held open across an acquirer call. Every `@Transactional` method lives in one bean, `PaymentWrites`, and the flows that call the acquirer aren't transactional, so a transaction can't leak across the network call.

| Current status | Capture | Void |
|---|---|---|
| `AUTHORIZED` | claim → `200` `CAPTURED` | claim → `200` `VOIDED` |
| `CAPTURED` | `200`, the current payment, no acquirer call | `409` |
| `VOIDED` | `409` | `200`, the current payment, no acquirer call |
| `CAPTURING`, claimed < 30 s ago | `409` `"capture in progress"`, `Retry-After: 1` | `409` |
| `CAPTURING`, claimed ≥ 30 s ago | re-drive: call the acquirer's capture again | `409` |
| `VOIDING` | the mirror image | the mirror image |
| `PENDING`, `UNKNOWN`, `DECLINED` | `409` naming the status | `409` naming the status |

The stale re-drive is safe because the acquirer's capture and void are idempotent on the authorization id. Capture is full capture only.

---

## Testing

```bash
./mvnw verify        # .\mvnw.cmd verify in PowerShell; Docker must be running
```

On the current source:

| Module | Tests |
|---|---|
| acquirer-simulator | 24 |
| payment-service | 220 |

Both modules report `Failures: 0, Errors: 0, Skipped: 0`. payment-service's count includes 64 parameterized cases from `PaymentStatusTest`, one for every ordered pair of the eight statuses.

The integration tests run against real PostgreSQL 16 and Redis 7 in Testcontainers. The schema is Flyway's, checked by `ddl-auto: validate`. The acquirer is `FakeAcquirer`, an in-process fake on the JDK's `HttpServer` that implements the simulator's contract and counts calls per reference. The shared test context uses a 1 s acquirer read timeout and 2 s Redis timeouts, against 2 s and 200 ms in production, so `tok_timeout` tests take a second and a cold Redis connection through Docker Desktop doesn't flake. `RedisDownTest` and `RedisPausedTest` keep the production 200 ms, because that is what they test.

| Promise | Proved by |
|---|---|
| No double charge under concurrency | `IdempotencyConcurrencyTest`: 20 simultaneous identical requests → every answer `201` or `409`, 1 payment, 1 acquirer call, and only one request reached Postgres |
| Redis is not the guard | `IdempotencyCacheTest`: a flushed Redis still replays from Postgres. `RedisDownTest`: Redis on a closed port, 10 concurrent retries, still 1 payment and 1 acquirer call. `RedisPausedTest`: a paused Redis container, payments still created and replayed |
| Redis can't give a wrong answer | `IdempotencyCacheTest`: an in-flight lock with another fingerprint is `409`, never `422`; unreadable, fingerprint-less and unreplayable entries are dropped; only the owner can release a lock; a cache fault after the database never turns a `201` into a `500` |
| No lost charge | `IdempotencyTest`: timeout → `502` with `paymentId` → retry → same payment, `AUTHORIZED`, two acquirer calls under one reference, one authorization recorded; a `PENDING` payment left by a crash is re-driven; a failure recording the outcome still returns the `paymentId`, and the retry recovers the charge |
| A `502` is never the stored answer | `IdempotencyTest`: after a timeout the key is still `IN_PROGRESS`, with no stored response and its lease released |
| Tenant isolation | `IdempotencyTest`: the same key in two tenants → two payments, each replaying its own. `PaymentCreationTest`, `PaymentTransitionTest`: another tenant's payment is `404` to read, capture and void |
| Capture and void cannot both win | `PaymentTransitionTest`: a barrier holds both requests until each has loaded the same version, and the loser's `409` detail shows `@Version` decided it; plus a free-running race repeated 10 times |
| Authorization before parsing | `SecurityBoundaryTest`: an unauthorized token with an invalid body → `403`, not `400`; the same for a token with no tenant |
| The token is really verified | `RealTokenTest`: real RS256 tokens against a JWKS served locally. A wrong issuer, an expired token, a token signed by another key and a malformed bearer value are each `401` |
| Nothing charges a card it can't answer | `PaymentContentNegotiationTest`: `Accept: text/plain` → `406`, no payment, no acquirer call |
| The schema is real | `PaymentPersistenceTest`: the CHECK constraints refuse a bad amount, currency and status directly in PostgreSQL |

---

## Known limitations

- **Client-credentials tokens can't create payments.** They carry no `tenant` claim, and real merchants integrate server-to-server, so this is the most significant gap. M7 adds a tenant-bound machine client to AuthCore.
- **`UNKNOWN` payments resolve only when the client retries.** A client that never retries leaves the payment `UNKNOWN`. M5 adds reconciliation against the acquirer.
- **No resilience around the acquirer** beyond timeouts: no retry, circuit breaker or bulkhead (M5).
- **A capture or void the acquirer refuses (`4xx`) leaves the payment `CAPTURING` or `VOIDING`.** A retry within 30 s gets `409` `"capture in progress"` (or `"void in progress"`). Every retry after that gets the same `502` "needs reconciliation" until M5's reconciliation resolves it. Locally, the realistic way to hit this is restarting the in-memory simulator, which then no longer knows the authorization.
- **An authorization the acquirer refuses (`4xx`) leaves the payment's status unchanged**: `PENDING` on a first attempt, `UNKNOWN` on a re-drive. The key's lease is released, so every retry with the same key re-drives and gets the same `502` until M5's reconciliation resolves it.
- **The Postgres lease has no owner fence.** A request that stalls past its 30 s lease, far above the acquirer's timeouts, can release or complete a key that a later request has since claimed. The cost is at most extra acquirer calls and a `502`, never a double charge: every attempt sends the same payment id as the acquirer's reference, and the acquirer deduplicates on it.
- **A stale Redis lock can delay a re-drive or a replay by up to 30 s (lock-ttl).** An `IN_PROGRESS` lock left behind by a crashed request, or by a release or completion that never reached Redis, answers every retry with a retryable `409` until it expires, even where Postgres would already allow a re-drive or replay. A release or completion is lost when its command times out, during a Redis pause for example. It never causes a double charge or a wrong answer; the retry succeeds once the lock lapses.
- **Idempotency rows are never purged.** Redis entries expire after 24 hours, but the Postgres rows remain and keep replaying.
- **A replay is a snapshot**, not the payment's current state.
- **Full capture only.** No partial captures, refunds or multi-capture.
- **Not behind GateKeeper yet.** No route and no rate limiting until M7.
- **Listing is `limit` only**, newest first, with no cursor. Keyset pagination arrives in M7.
- **The acquirer link is unauthenticated**, by design (see [Running it](#running-it)).
- **The audience is not validated.** AuthCore sets `aud` to the client id (`authcore-client` in the token above), not to the resource, so scope plus permission act as the audience check, as in ledger-service. A token carrying `payments:*` that was presented to another platform service can be replayed here.
- **Access tokens typed `at+jwt` (RFC 9068) would be rejected** by Spring Security 7's default type validator. AuthCore sets no `typ` today; if it adopts RFC 9068, this service needs a validator that accepts `at+jwt`.
- **An unknown `kid` re-fetches AuthCore's JWKS on every request.** Spring's default JWK source doesn't rate-limit refreshes, so a forged token with a made-up `kid` costs one call to AuthCore per request. ledger-service has the same default. Rate-limiting belongs with GateKeeper's edge protection (M7).

---

## Roadmap

| | Milestone | Status |
|---|---|---|
| M0 | Multi-module skeleton, resource server verifying AuthCore JWTs | ✅ |
| M1 | Authorize, capture, void; state machine; acquirer simulator | ✅ |
| M2 | Idempotent creation: Postgres guarantee, Redis fast path | ✅ |
| M3 | Transactional outbox with a polling relay → Kafka, keyed by merchant; history-service consuming through an inbox | planned |
| M4 | settlement-service: per-merchant payout batches from captured payments | planned |
| M5 | Resilience4j around the acquirer; reconciliation of `UNKNOWN` and refused payments | planned |
| M6 | Micrometer/Prometheus, Grafana, correlation ID across HTTP, outbox and Kafka | planned |
| M7 | GateKeeper routing and rate limiting; tenant-bound machine clients; keyset pagination | planned |
| M8 | Load test with committed scripts and results | planned |

---

## License

MIT
