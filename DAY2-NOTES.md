# Day 2 Notes

Working notes for the Day 2 exercise. Sections 2 and 3 are answers to the
guide's discussion questions — they are a starting position to argue with, not
a marking scheme.

---

## 1. What actually changed today

Day 1 already had DTOs, a global exception handler, and an externalized
`user-service.base-url`. So Day 2 was mostly about **sharpening** those rather
than introducing them. Concretely:

| Change | Why |
|---|---|
| Removed `sourceNote` from `OrderResponse` | It was a Day-1 teaching note ("user details fetched from User Service over HTTP") sitting in the public contract. Consumers would have started depending on it. |
| Nested the customer instead of `userId`/`userName`/`userEmail` | A reader can now see which fields describe the order and which describe the customer. |
| Split `dto.UserResponse` into `client.UserServiceUser` + `dto.CustomerSummary` | One class was doing two jobs: parsing someone else's response *and* defining our own contract. A rename in User Service would have leaked straight through. |
| `UserServiceException` carries a `Reason` enum instead of a boolean | A boolean can only express two outcomes. There are four. |
| Added 502 / 503 / 504 as distinct outcomes | "Down", "slow", and "answered with an error" are different operational problems and need different responses. |
| Added a `code` field to every error body | `status` alone cannot distinguish the two different 404s. |
| Added a `MethodArgumentTypeMismatchException` handler | `GET /api/orders/abc` was returning **500** — blaming the server for the caller's malformed request. Now 400. |
| Stopped echoing raw exception text on 500 | The old handler put `ex.getMessage()` in the response body. Internal detail now goes to the log; the caller gets a stable message. |
| Seeded order `103` → user `999` | There was no way to demonstrate the downstream-404 case. The README claimed order `102` did this; it didn't. |
| Added logging in `UserClient` | Step 4 of the failure exercise says "observe the response **and logs**" — there was nothing in the logs to observe. |

## 2. Failure exercise — what was observed

| Situation | Response | Verified |
|---|---|---|
| Order missing | 404 `ORDER_NOT_FOUND` | yes |
| User missing downstream | 404 `USER_NOT_FOUND` | yes |
| User Service stopped | 503 `USER_SERVICE_UNAVAILABLE` | yes |
| User Service unreachable/slow | 504 `USER_SERVICE_TIMEOUT` | yes (base-url pointed at a blackholed IP) |
| User Service returns 5xx | 502 `USER_SERVICE_ERROR` | **not exercised live** — reasoned from the code path only |

Log line when User Service was down:

```
WARN c.o.orderservice.client.UserClient : User Service is not reachable at
     http://localhost:8081 (java.net.ConnectException: Connection refused: getsockopt)
```

The most useful thing observed: with User Service stopped,
`GET /api/orders/500` **still returned 404 `ORDER_NOT_FOUND`**. Order Service
checks what it owns before it touches the network, so an unknown order never
depends on a healthy dependency. Ordering the code that way is the cheapest
resilience there is — no library involved.

## 3. Mini design challenge — Order + Payment Service

*An order is created, but Payment Service is temporarily unavailable.*

**Should Order Service report success?** No. Returning 201 Created implies the
order is paid for and will ship. Something has to reconcile that lie later, and
by then the customer has an email saying their order went through.

**Should it report that payment could not be completed?** Also not quite —
that reads as "your payment failed", and the customer will retry, likely
producing a duplicate later. The honest answer is a third state: *the order is
recorded, payment has not been attempted yet*.

**So what should the API return?** `202 Accepted`, with the order id and an
explicit status:

```json
{
  "orderId": 5501,
  "status": "PENDING_PAYMENT",
  "paymentStatus": "NOT_ATTEMPTED",
  "message": "Order recorded. Payment will be attempted shortly.",
  "statusUrl": "/api/orders/5501"
}
```

The client gets something actionable — an id and somewhere to poll — instead of
a naked error. This only works if Order Service can persist an order in a
not-yet-paid state, which is a *data model* decision, not an error-handling one.
That is the real lesson: resilience shows up in the schema before it shows up in
a retry policy.

**Should Order Service read Payment Service's database directly?** No.

- It would couple us to their schema — their migration becomes our outage, and
  they have no way to know we exist.
- Their invariants live in their code, not their tables. Reading rows directly
  means re-implementing rules we cannot see, and getting them subtly wrong.
- A shared database means neither team can deploy independently, which removes
  the only thing microservices bought us in exchange for all this network pain.
- It also fails to solve the problem: if Payment Service is down, its database
  is probably unhealthy too.

**What belongs in the API contract?** The endpoints and methods; the request and
response shapes; the full set of order statuses *as an enumerated list* (a
consumer must be able to write a switch); which fields are optional and when;
the status codes and their `code` values; idempotency behaviour for retried
order creation; and the timeout callers should expect.

**Now vs. later:**

| Solve now | Needs a later resilience/messaging design |
|---|---|
| Don't report false success | Retry with backoff |
| A payment status the model can actually represent | Circuit breaker |
| Distinguish "declined" from "unavailable" | Queue/outbox for guaranteed delivery |
| Timeouts on every outbound call | Idempotency keys and deduplication |
| Log the failure with enough context to debug | Distributed tracing |
| A status endpoint the client can poll | Saga / compensating transactions |

The left column is design discipline and costs nothing but attention. The right
column is infrastructure, and doing it early usually produces a worse version of
what a library gives you later.

## 4. Self-assessment

**1. What is an API contract?**
The promise a service makes about how it can be called and what comes back:
endpoints, inputs, outputs, status codes, and behaviour under failure. It is the
part consumers are allowed to depend on — and by implication, everything *not*
in it is free to change.

**2. Why can exposing an entity directly become a problem?**
The entity exists to serve persistence, so it changes for persistence reasons —
a new audit column, a rename, an internal flag. If it *is* the response, every
one of those changes silently becomes a public API change. `User.internalNote`
is the live example here: it exists internally, and no consumer has ever seen it.

**3. What is the purpose of a DTO?**
To make the boundary a deliberate choice. It decouples the wire shape from the
internal shape so each can change for its own reasons.

**4. Difference between a missing resource and a downstream failure?**
A missing resource is a fact: the answer is "that does not exist", it is correct,
and retrying changes nothing. A downstream failure means *we don't know* — the
resource may exist perfectly well and we simply couldn't ask. The first is the
client's problem (404), the second is ours (5xx), and retrying only makes sense
for the second.

**5. Why should a service URL be configurable?**
Because the URL describes the *environment*, not the *logic*. Dev, test, and
prod run identical code against different addresses; if the address is compiled
in, promoting a build means editing and rebuilding it, and the artifact you
tested is no longer the artifact you shipped.

**6. What happened when I stopped User Service?**
`ConnectException: Connection refused`, surfaced as a `ResourceAccessException`.
Before today that came back as a generic failure; the endpoint that doesn't need
User Service kept working throughout.

**7. How did I make that failure more understandable?**
Caught each failure mode separately in `UserClient`, tagged it with a `Reason`,
and mapped that to a distinct status and `code`. The message names the URL that
failed, so the response says *what* broke and *where* without a log dive.

**8. Easiest part?**
The DTO work. Once the split is clear, it's mechanical.

**9. Most thinking?**
Choosing status codes for downstream failures. 503 vs 504 vs 502 is a judgment
call about who the caller should blame and whether retrying is worth it. Also:
resisting the urge to add retries, which the guide explicitly rules out today.

**10. To understand better before Day 3?**
- When retries are safe — presumably only for idempotent reads like this one?
- Where a circuit breaker belongs relative to the client class.
- Whether an error `code` taxonomy should be shared across services or owned
  per-service. Right now `INVALID_PARAMETER` is duplicated in both, deliberately.
- How this changes once there is a real database instead of a seeded map.

## 5. Open question about the guide's quality check

> "Did you avoid unnecessary duplication between the two services?"

Both services now have a near-identical `GlobalExceptionHandler` and error
envelope. That duplication was kept **on purpose**: extracting it into a shared
library would mean neither service could change its error format without a
coordinated release, which is the coupling this whole exercise is trying to
avoid. The word doing the work in that question is *unnecessary* — duplication
across a service boundary is usually the cheaper of the two mistakes. Worth
raising for discussion rather than treating as settled.
