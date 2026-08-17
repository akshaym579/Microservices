# Day 4 Notes

Today was about deliberately breaking things. Every number below was measured on
the running system, not reasoned about.

---

## 1. What was built

A fourth service, **`payment-service`** on port 8083, deliberately built so it
can be made to misbehave on demand:

```bash
curl.exe -X POST "http://localhost:8083/admin/behaviour?mode=SLOW&delayMs=6000"
curl.exe -X POST "http://localhost:8083/admin/behaviour?mode=FAIL"
curl.exe -X POST "http://localhost:8083/admin/behaviour?mode=DECLINE"
curl.exe -X POST "http://localhost:8083/admin/behaviour?mode=OK&failures=1"
curl.exe http://localhost:8083/admin/stats
```

`/admin/stats` reports `callsReceived`, which turned out to be the most valuable
thing built today — it is how you *prove* a circuit breaker is working, rather
than assuming it.

Order Service gained `POST /api/orders`, which validates the customer with User
Service, records the order, then charges Payment Service through a client wrapped
in Resilience4j retry + circuit breaker, with RestTemplate timeouts underneath.

```
Client → Gateway → Order Service ──── GET /api/users/{id} ───▶ User Service
                        │
                        └─ POST /api/payments (timeout, retry, breaker) ─▶ Payment Service
```

`GET /admin/circuit-breaker` on Order Service reports live breaker state, and
`POST /admin/circuit-breaker/reset` forces it closed. Both are test surface, and
the reset endpoint exists because of a mistake described in section 6.

## 2. Timeouts — measured

Payment Service set to sleep 6 seconds. Retry disabled so the timeout is isolated.

| Read timeout | Waited | Result |
|---|---|---|
| 30s (effectively none) | **6.1s** | 201 PAID |
| 2s | **2.2s** | 202 PENDING_PAYMENT |

The caller stops waiting at the timeout, exactly as configured. Note what the
timeout did *not* do: Payment Service still spent its 6 seconds, still captured
the charge, and is still slow. **A timeout protects the caller, not the
dependency** — the request that mattered to the user got a truthful answer in
2.2s instead of being held hostage for 6.

The real danger is what happens with hundreds of concurrent orders: each one
holds a thread for the full wait. Without a timeout, Order Service runs out of
threads and starts failing requests that have nothing to do with payments. That
is how one slow service takes down a healthy one.

## 3. Retries — measured

`max-attempts=3`, `wait-duration=300ms`, retrying only `PaymentUnavailableException`.

| Scenario | Result | Payment calls received | Why |
|---|---|---|---|
| First attempt fails, then recovers | 201 PAID in 0.45s | **2** | Attempt 1 failed, attempt 2 succeeded |
| Every attempt fails | 202 PENDING_PAYMENT in 0.65s | **3** | Retry budget exhausted, then it stopped |
| Business decline | 402 PAYMENT_DECLINED in 0.03s | **1** | A decline is a final answer, not a glitch |

The third row is the important one. A declined card is Payment Service working
*correctly*. Retrying it would be pointless load, would delay a truthful answer
to the customer, and — because the breaker only records
`PaymentUnavailableException` — declines cannot open the circuit either. A busy
day of legitimately declined cards must not look like an outage.

This is configuration, not code:

```properties
resilience4j.retry.instances.paymentService.retry-exceptions=...PaymentUnavailableException
resilience4j.circuitbreaker.instances.paymentService.record-exceptions=...PaymentUnavailableException
```

## 4. Circuit breaker — measured

Config: count-based window of 6, minimum 4 calls, 50% failure threshold, 10s open,
2 permitted probes in half-open, automatic open→half-open.

Payment Service failing continuously:

| Request | Result | Payment calls | Breaker state |
|---|---|---|---|
| start | — | 0 | CLOSED |
| 1 | 202 in **0.66s** | 3 | CLOSED (3 buffered, minimum is 4) |
| 2 | 202 in 0.34s | **4** | **OPEN** (4/4 failed = 100%) |
| 3 | 202 in **0.02s** | **4** | OPEN, 2 calls blocked |
| 4 | 202 in **0.02s** | **4** | OPEN, 3 calls blocked |

Then Payment Service recovers and the 10s window elapses:

| Step | Result | Payment calls | Breaker state |
|---|---|---|---|
| after wait | — | 4 | **HALF_OPEN** |
| probe 1 | 201 in 0.03s | 5 | HALF_OPEN (1 of 2 probes) |
| probe 2 | 201 in 0.03s | 6 | **CLOSED** |

Two things this proves rather than asserts:

- **`callsReceived` froze at 4.** Requests 3 and 4 never touched Payment Service.
  That is the entire point of the pattern — a struggling service gets *less*
  traffic while it is struggling, not more.
- **0.66s → 0.02s, a 33× speedup.** When the circuit is open the caller stops
  paying the cost of discovering the failure. That latency is what protects the
  caller's threads.

Notice also that request 2 only made **one** call instead of three: the breaker
opened mid-retry and blocked the remaining attempts. Retry and circuit breaker
compose — retry handles the blip, the breaker stops retry from becoming a flood.

### The three states

```
                  failure rate ≥ threshold
                    (over a minimum
                     number of calls)
        ┌────────┐ ──────────────────────▶ ┌────────┐
        │ CLOSED │                         │  OPEN  │
        └────────┘ ◀────────────────────── └────────┘
             ▲       probes succeeded           │
             │                                  │ wait duration
             │                                  │ elapses (10s)
             │                                  ▼
             │       probe fails         ┌───────────┐
             └───────────────────────────│ HALF_OPEN │
                    (back to OPEN)       └───────────┘
```

**CLOSED** — normal. Calls go through; outcomes are recorded in a sliding window.
Failures are allowed, and a few of them change nothing. It only reacts once there
is enough evidence (`minimum-number-of-calls`), which stops one unlucky failure
at startup from tripping the system.

**OPEN** — the breaker has concluded the dependency is unhealthy. Calls are
rejected immediately without a network attempt (`CallNotPermittedException`),
which is what produced the 0.02s responses. This protects *both* sides: the
caller stops burning threads waiting, and the failing service gets breathing room
to recover instead of being hammered by traffic it cannot serve.

**HALF_OPEN** — after the wait duration, a limited number of probe calls are
allowed through. This is the only way to find out whether the dependency
recovered; the alternative is staying open forever or flooding it to find out.
If the probes succeed the breaker closes; if they fail it opens again for another
full wait. Note the probes are *real customer requests*, not synthetic pings —
a small number of users pay the cost of discovering that recovery happened.

## 5. Safe vs unsafe retry — measured

Three identical `POST /api/payments` calls:

| | Payments captured | Distinct paymentIds |
|---|---|---|
| With `Idempotency-Key: order-777` | **1** | 1 (same id returned 3×) |
| Without a key | **3** | 3 different ids |

Without a key, retrying a POST charged the customer three times. This is why
"just add retries" is dangerous advice: the retry logic is the easy half, and the
downstream contract is the half that decides whether it is safe.

`GET /api/users/1` is naturally idempotent — reading twice changes nothing, so
retrying is free. `POST /api/payments` is not, unless the *server* makes it so.
Order Service sends `Idempotency-Key: order-{orderId}`, so all three of its retry
attempts can only ever produce one charge.

**Idempotency is a property of the operation's contract, not of the HTTP verb.**
POST is not "unsafe to retry" in the abstract; it is unsafe to retry *unless the
server promises deduplication*. That promise belongs in the API contract, because
the caller cannot verify it.

And the promise has to survive concurrency, not just repetition. The sequential
test above passed while the implementation was still broken for overlapping
attempts — see section 6, where that gap produced three real charges for one
order.

## 6. What went wrong while testing

Worth recording, because the mistake is more instructive than the fix.

Running the three retry scenarios back to back produced nonsense: the "every
attempt fails" case showed 2 calls instead of 3, and the "decline" case showed
**0** calls and a 202 instead of the 402 it should have returned.

Neither was a bug. The circuit breaker had opened during the earlier scenario and
was still open — so it cut the second test's retries short and blocked the third
test entirely. Resetting Payment Service's counters between tests did nothing,
because the relevant state lived in *Order Service*.

Two lessons:

- **Resilience patterns are stateful across requests.** Every test after the
  first runs against a system that remembers. Test isolation now requires
  resetting the breaker, which is why `/admin/circuit-breaker/reset` exists.
- **The behaviour was correct and still looked like a bug.** In production this
  is the confusing part of circuit breakers: a service reports failures for a
  dependency it never called, and the timing has no obvious relationship to the
  request that failed.

An earlier timeout measurement was also invalid: order IDs restart at 200 when
Order Service restarts, so the idempotency key `order-200` collided with a
previous run and Payment Service replayed the cached charge in 0.2s instead of
sleeping. The demonstration only became valid after clearing payment state
between runs. Idempotency working correctly looked exactly like a broken test.

### A real triple-charge, found by looking at the logs

The worst bug of the day only appeared during a manual walkthrough, after the
notes had already been written. Running the slow-dependency test with retries
enabled produced this:

```
Call 1 captured payment 2e2a69d6... for order 202
Call 2 captured payment fec7eee4... for order 202
Call 3 captured payment f05a96aa... for order 202
```

Three charges for one order — all three sent with the *same* idempotency key
`order-202` — while the customer's order read `PENDING_PAYMENT`. The customer
would have been charged three times for an order the system says is unpaid.

The cause was in Payment Service: `charge()` read the idempotency store before
doing the work and wrote to it after. Each attempt timed out at 2s while the
6-second sleep continued in the background, so all three retries arrived and
found an empty cache before any of them had finished.

**A check-then-act idempotency store is not idempotent.** The key has to be
claimed atomically, before the work starts, so that concurrent attempts with the
same key cannot all pass the check. The fix is a single `computeIfAbsent`:

```java
Payment payment = paymentsByIdempotencyKey.computeIfAbsent(idempotencyKey, key -> {
    captured.set(true);
    return capture(orderId, amount, call);
});
```

Same scenario after the fix — 3 calls, **1 capture**, 2 replays:

```
Call 1 captured payment 44258779... for order 203
Call 2 replayed idempotency key order-203, returning existing payment 44258779...
Call 3 replayed idempotency key order-203, returning existing payment 44258779...
```

The outcome improved in a way that was not the point but is worth noticing: the
client now gets **201 PAID in 6.1s** instead of 202 in 6.7s. Attempts 2 and 3
block on the in-flight charge rather than starting their own, and attempt 3
receives the result 1.4s later — inside its 2s timeout. Serialising duplicate
work turned a lost payment into a completed one.

The lesson generalises past this bug: **the retry was correct, the timeout was
correct, and the combination was still dangerous** because the downstream
contract could not actually keep the promise the caller relied on. Resilience
patterns compose with the contract, not just with each other.

## 7. Graceful failure — what the client gets

| Payment Service is | Order Service returns | `code` | Order status |
|---|---|---|---|
| Working | 201 Created | — | PAID |
| Declining the card | 402 Payment Required | `PAYMENT_DECLINED` | PAYMENT_DECLINED |
| Slow past the timeout | 202 Accepted | — | PENDING_PAYMENT |
| Down / unreachable | 202 Accepted | — | PENDING_PAYMENT |
| Failing repeatedly (breaker open) | 202 Accepted | — | PENDING_PAYMENT |

```json
{
  "orderId": 200,
  "product": "Cable",
  "amount": 19.00,
  "status": "PENDING_PAYMENT",
  "customer": { "id": 1, "name": "Akshay", "email": "akshay@example.com" },
  "payment": {
    "status": "NOT_COMPLETED",
    "message": "Payment has not been completed. The order is recorded but will not be fulfilled until payment succeeds."
  }
}
```

Three deliberate decisions here:

- **202, not 201.** The order exists; the payment does not. 201 Created would
  imply a completed purchase and the customer would expect delivery.
- **202, not 500.** Nothing is broken from the client's perspective — their
  request was accepted and recorded. A 500 would invite them to retry and create
  a second order.
- **402 is different from all of it.** A decline is a *final answer* from a
  healthy system, so it gets a real error status. The other rows are "we don't
  know yet".

In the logs, by contrast, the raw cause is preserved:
`Payment Service is not reachable at http://localhost:8083 (java.net.ConnectException: Connection refused)`.
The operator needs the stack trace; the customer needs a sentence.

### An honest problem with this fallback

The message originally said payment "will be retried". **Nothing in this system
retries it.** There is no reconciliation job, so that was a promise the platform
does not keep — precisely the untruthful fallback this day warns about, written
without noticing. The wording now states only what is true: the order is recorded
and will not be fulfilled until payment succeeds.

A real implementation needs a background process that picks up `PENDING_PAYMENT`
orders and completes or cancels them. Until that exists, these orders are stuck,
and the API should not imply otherwise.

## 8. Mini scenario — Payment Service slow, hundreds of orders

| Pattern | The specific problem it solves |
|---|---|
| **Timeout** | Stops each request holding a thread for the full delay. Without it, hundreds of waiting requests exhaust the thread pool and Order Service fails *entirely* — including requests that need no payment at all. |
| **Limited retry** | Recovers from the blips: one dropped packet or one restarting instance should not become a customer-visible failure. **Limited** because unbounded retries multiply load on a service that is slow *because* it is overloaded. |
| **Circuit breaker** | Once the dependency is clearly unhealthy, stops calling it at all. Removes the load that retries would otherwise add, and lets Order Service answer in 0.02s instead of 2s. It is the pattern that stops retries from becoming the attack. |
| **Graceful fallback** | Decides what the customer sees when all of the above have failed. Turns a connection error into a recorded order and a truthful 202. |

They solve different problems and the order matters: **timeout** decides how long
one attempt may take, **retry** decides how many attempts, **circuit breaker**
decides whether to attempt at all, and **fallback** decides what to say when the
answer is no.

The trap is that retry alone makes an overload *worse*. A struggling service
receiving 3× traffic from every caller's retry logic is a well-documented way to
turn a slowdown into an outage. Retry is only safe in the presence of a breaker.

## 9. Self-assessment

**1. What problem does a timeout solve?**
Unbounded waiting. Without one the caller waits as long as the dependency takes,
holding a thread the whole time. It converts "slow forever" into a definite
failure the caller can act on. It protects the caller, not the dependency.

**2. Why can retries make an outage worse?**
Because the usual reason a service is failing is that it is overloaded, and every
caller retrying 3× triples the load. Retries are applied by the *callers*, so
they scale with the number of clients — exactly when the service can least afford
it. A slowdown becomes an outage.

**3. Why should retries be limited?**
An unbounded retry never gives up, so the request never returns and the thread is
never released — the same problem a timeout solves, reintroduced. A limit
guarantees the caller reaches a definite answer in bounded time.

**4. What is idempotency and why does it matter?**
An operation is idempotent when performing it several times has the same effect
as performing it once. It matters because a network failure is ambiguous: a
timeout does not tell you whether the request was processed. If the operation is
idempotent, retrying is safe. If not, retrying may double-charge — measured
above: 3 retries without a key produced 3 payments.

**5. What problem does a circuit breaker solve?**
Repeatedly calling a dependency that is already known to be failing. Each such
call costs the caller a timeout and costs the dependency load it cannot serve. The
breaker remembers recent outcomes so each request doesn't rediscover the outage.

**6. What happens when a circuit is Open?**
Calls are rejected immediately without any network attempt. The caller fails fast
(0.02s measured) and the dependency receives no traffic — `callsReceived` stayed
frozen at 4 across requests that would otherwise have made 6 calls.

**7. What is the purpose of Half-open?**
To find out whether the dependency recovered without going back to full traffic.
A limited number of probe calls are allowed; success closes the breaker, failure
reopens it. Without it the breaker would either stay open forever or flood a
still-broken service.

**8. What is a fallback?**
Alternative behaviour when the normal path cannot complete — a cached value, a
default, a degraded response, or here, recording the order as PENDING_PAYMENT and
returning 202.

**9. Why must a fallback never falsely report success?**
Because everything downstream believes it. A 201 for an unpaid order means the
warehouse ships goods that were never paid for and the customer gets a
confirmation email. The failure does not disappear — it gets discovered later by
someone with less context and no way to fix it cleanly. A fallback changes *what
you say*, never *what is true*.

**10. Order → Payment: how do the four work together?**
The timeout bounds a single attempt at 2s. Retry allows up to 3 attempts for
transient failures only, with an idempotency key so repeats cannot double-charge.
The circuit breaker watches those attempts and, after enough failures, stops
calling Payment Service entirely for 10s — then probes with a couple of real
requests to see if it recovered. If everything fails, the fallback records the
order as PENDING_PAYMENT and returns 202 with a truthful message, while the log
keeps the real exception. A decline bypasses all of it: not retried, not recorded
as a breaker failure, returned as 402.

## 10. Deliberately not done

- **No background reconciliation** for `PENDING_PAYMENT` orders (section 7).
- **Idempotency is in-memory only.** The key is now claimed atomically (section 6),
  so concurrent attempts cannot double-charge, but the store is a `ConcurrentHashMap`
  inside one process. A real implementation needs the claim to be durable and
  shared across instances — a unique constraint in the database, not a map.
- **All state is in memory.** Every restart loses orders and payments, and order
  IDs restart at 200 — which invalidated a measurement before it was noticed.
- **Payment Service is not routed through the gateway.** It is an internal
  service; only Order Service calls it. `/api/payments/**` deliberately returns
  `NO_ROUTE` from the gateway.
- **No bulkhead / rate limiter.** Resilience4j provides both; neither was needed
  to make today's points.
- **The `/admin` endpoints are test surface.** In a real service they would be
  behind authentication or excluded from the production build entirely.

## 11. Questions for Day 5

- Where should the retry live — in Order Service, or in the gateway? If both
  retry, three attempts becomes nine.
- How do breaker settings get chosen? 50% over 4 calls is arbitrary; what does a
  real service base those numbers on?
- If a `PENDING_PAYMENT` order is completed later by a background job, how does
  the customer find out? That seems to need messaging rather than HTTP.
- Every service now has its own breaker state, invisible from outside. How would
  an operator see "which breakers are open right now" across 20 services?
