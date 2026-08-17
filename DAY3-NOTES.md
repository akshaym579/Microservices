# Day 3 Notes

Two services became a small system today: a gateway in front, and a clearer
story about who owns what. Sections 4–7 answer the guide's discussion questions.

---

## 1. What was built

One new module, `api-gateway`, on port **8080**, using Spring Cloud Gateway
(`2023.0.3`, matching Boot 3.3.5). Two routes, both pointing at properties
rather than literals:

```yaml
routes:
  - id: user-service
    uri: ${backend.user-service.url}
    predicates: [ Path=/api/users/** ]
  - id: order-service
    uri: ${backend.order-service.url}
    predicates: [ Path=/api/orders/** ]
```

Neither backend service was changed. That is the point worth noticing: the
gateway was added *in front of* a working system without touching it.

```
                    ┌──────────────────────┐
Client ────────────▶│  API Gateway  :8080  │
                    └──────────┬───────────┘
                     /api/users/**  /api/orders/**
                          │             │
                          ▼             ▼
                 ┌──────────────┐  ┌───────────────┐
                 │ User  :8081  │◀─│ Order  :8082  │
                 └──────────────┘  └───────────────┘
                        owns users        owns orders
```

Note the arrow from Order back to User. The gateway did **not** replace
service-to-service communication — Order Service still calls User Service
directly on 8081. The gateway is the *client's* entry point, not an internal
message bus. Routing internal traffic through it would add a hop and a single
point of failure for calls that never needed to leave the platform.

## 2. The interesting finding: the gateway has its own API contract

Days 1–2 built a careful error contract — `code`, distinct statuses, 503 vs 504.
Adding a gateway silently introduced a **new error surface that didn't follow
any of it**, because these responses never reach a backend:

| Situation | Before the fix | After |
|---|---|---|
| Path with no route (`/api/payments/1`) | 404, Spring's generic body, no `code` | 404 `NO_ROUTE` |
| Backend process down | **500**, generic body | 503 `BACKEND_UNAVAILABLE` |
| Backend accepts but stalls | 504, generic body | 504 `BACKEND_TIMEOUT` |

A downed backend being reported as **500 Internal Server Error** was the worst
of these: it tells the client "we're broken" when the truth is "we can't reach
a dependency" — the exact distinction Day 2 spent an hour on. The gateway is now
the only thing a client sees, so its error contract matters *more* than any
backend's, not less.

Fixed with a `GatewayErrorAttributes extends DefaultErrorAttributes` that emits
the same six-field envelope the two services already use.

Two things that only showed up by testing rather than reasoning:

- **A connect timeout is a `ConnectException`.** Pointing a route at a blackholed
  IP produced `BACKEND_UNAVAILABLE`, not `BACKEND_TIMEOUT`. That's defensible
  (we never got a connection), but it means `BACKEND_TIMEOUT` only fires for
  *response* timeouts — a backend that accepted the connection and went quiet.
- **Spring Cloud Gateway wraps a response timeout in `ResponseStatusException`.**
  The first version keyed off the exception type, matched that branch, and
  produced the right 504 with the wrong `code`. Now the code is derived from the
  resolved status, so both routes to 504 agree.

Verified with a throwaway TCP listener that accepts a connection and never
replies — the 504 fired at 3.1s, matching `response-timeout: 3s`.

### Two different 503s

With User Service stopped, these two calls both return 503, from different
components, for different reasons:

```
GET :8080/api/orders/100  → 503 USER_SERVICE_UNAVAILABLE   (order-service said this)
GET :8080/api/users/1     → 503 BACKEND_UNAVAILABLE        (the gateway said this)
```

Status alone cannot tell them apart. `code` can: the first means Order Service
is healthy but its dependency isn't; the second means Order Service's *peer* is
unreachable from the front door. Different on-call responses.

## 3. Service discovery — the problem, not the implementation

Not implemented today, per the guide. The problem it solves, stated plainly:

Every address in this system is written down somewhere by a human. That works
for three services on one laptop. It stops working when:

- **Instances multiply.** `backend.order-service.url` names *one* address. Three
  instances of Order Service need load balancing, and a static URL cannot express
  "any healthy one of these".
- **Addresses stop being stable.** A container restart gives a new IP. Nothing
  restarts every service to tell it the new address.
- **Health changes faster than config.** A static URL will happily point at a
  dead instance until someone edits a file.

Discovery replaces "where is Order Service" (an address, written by a person,
correct until it isn't) with "who is currently healthy and answering as
`order-service`" (a query, answered at call time). The registry becomes the
source of truth, and services register themselves on startup.

The honest trade-off: it's another piece of infrastructure that must be highly
available, and when it's wrong, *everything* is wrong. That's why the guide has
us feel the pain of static URLs first.

## 4. Challenge 2 — configuration inventory

Audited all three services. **No hard-coded URLs or ports in any Java file** —
grep for `localhost`, `http://`, and port literals across `**/*.java` returns
nothing. Everything below lives in `application.properties` / `application.yml`.

| Service | Property | Category |
|---|---|---|
| api-gateway | `server.port` | environment |
| api-gateway | `backend.user-service.url` | **service location** |
| api-gateway | `backend.order-service.url` | **service location** |
| api-gateway | `spring.cloud.gateway.httpclient.connect-timeout` | resilience tuning |
| api-gateway | `spring.cloud.gateway.httpclient.response-timeout` | resilience tuning |
| order-service | `server.port` | environment |
| order-service | `user-service.base-url` | **service location** |
| order-service | `user-service.connect-timeout-ms` | resilience tuning |
| order-service | `user-service.read-timeout-ms` | resilience tuning |
| user-service | `server.port` | environment |

Three service locations across three services. The count is the story: it grows
faster than the service count, because it tracks *edges*, not nodes.

### Values a real enterprise platform would need to manage consistently

1. **Service locations** — every base URL / hostname / port
2. **Credentials and secrets** — DB passwords, API keys, client secrets, tokens
3. **Database connection details** — URL, pool sizes, schema
4. **Timeouts and resilience settings** — connect, read, retry counts, circuit breaker thresholds
5. **Feature flags** — per environment, changed without a deploy
6. **Observability** — log levels, metrics/tracing endpoints, sampling rates
7. **Security** — TLS certificate paths, CORS origins, allowed issuers, JWT keys
8. **Rate limits and quotas**
9. **Message broker / queue endpoints** (once messaging arrives)
10. **Environment identity** — which environment this instance believes it is

### What becomes difficult at 15–20 services

- **The edge problem.** Service locations scale with *connections*, not services.
  Moving User Service to a new port today means editing two files. In a
  20-service platform it could mean editing a dozen, and missing one produces an
  outage that only appears on the path nobody tested.
- **Environment drift.** Four environments × 20 services = 80 property files that
  are supposed to differ in exactly the intended ways. Nothing enforces that.
  Bugs become "works in test, fails in prod" with no diff to look at.
- **Secrets in plain text.** Fine with zero secrets today. At 20 services there
  are real credentials, and they cannot live in files next to the code.
- **No global view.** Nobody can answer "what is the read timeout for every
  service in staging?" without opening 20 repos.
- **Restart to change anything.** Every tweak is a deployment.
- **Duplicated policy.** "2 second timeout" is currently written in four places.
  A platform-wide decision to change it becomes 20 pull requests.

This is the argument for a central configuration service and for discovery —
both replace "written down in N places" with "resolved from one place at
runtime". Neither is worth adding to a three-service system.

## 5. Database ownership

| Data | Owner | Everyone else |
|---|---|---|
| Users | User Service | asks via `GET /api/users/{id}` |
| Orders | Order Service | asks via `GET /api/orders/{id}` |
| Payments | Payment Service | asks via its API |
| Products | Product Service | asks via its API |

Order Service already demonstrates the rule: `Order` holds a `userId` and
nothing else about the user. That is a **reference**, not a copy. The name and
email in the response are fetched at request time and never stored.

**Should Order Service query the User table directly for a name?** No.

- **Their schema becomes our contract.** A column rename in User Service — an
  internal decision, invisible to them — breaks Order Service in production.
  There is no review, no version, no deprecation window, because they don't know
  we're reading it.
- **Rules live in code, not tables.** Which users are soft-deleted? Suspended?
  GDPR-erased? User Service's API applies those rules. Raw SQL bypasses all of
  them, so we'd return a name User Service would have refused to give us.
- **`internalNote` is right there.** The `User` entity has a field the API
  deliberately withholds. Direct table access hands it over, and nothing in the
  code says it shouldn't.
- **Independent deployment dies.** A shared table means schema migrations must be
  coordinated across teams. That is precisely the coupling microservices are
  paying for.
- **It doesn't even solve the problem.** If User Service is down, its database
  is usually unhealthy too. Reading directly trades a clean 503 for a stack trace.

**Why physical separation can come later.** Ownership is a rule about *who is
allowed to write and change* the data; physical separation is one way to
*enforce* it. A team can start on one database server with a schema per service
and keep the boundary honest through code review and per-service database
credentials that simply lack permission on other schemas. Splitting later is then
an operational task, because no code ever assumed shared access. Allow
cross-schema joins on day one and the same split becomes a rewrite — every join
is a hidden dependency you now have to find. **The discipline is the hard part;
the physical split is the easy part.**

## 6. Mini enterprise architecture challenge

*A customer creates an order. It needs customer information and payment.*

```
Client
  │  POST /api/orders
  ▼
API Gateway ──────────────────────────────────┐
  │ routes /api/orders/**                     │ also routes
  ▼                                           │ /api/users/**, /api/payments/**
Order Service ──── GET /api/users/{id} ──▶ User Service      (owns users)
  │
  └───────────── POST /api/payments ──────▶ Payment Service  (owns payments)
  │
owns orders
```

| Question | Answer |
|---|---|
| Who receives the client request? | API Gateway — the single entry point |
| Who owns order data? | Order Service |
| Who owns user data? | User Service |
| Who owns payment data? | Payment Service |
| How does Order get user info? | `GET /api/users/{id}` over HTTP, never the User table |
| What if Payment is unavailable? | Record the order as `PENDING_PAYMENT`, return **202 Accepted** with an order id and a status URL — never a false 201 |
| What should be configuration? | All three service locations, every timeout, DB credentials, payment provider keys and endpoint |

The Payment answer is Day 2's design challenge unchanged, and it still hinges on
the data model rather than error handling: Order Service can only be honest about
a failed payment if "order exists, payment not attempted" is a state its schema
can represent. Resilience shows up in the schema before it shows up in a retry.

Note the gateway does *not* sit between Order Service and Payment Service. It is
a client entry point, not an internal router.

## 7. Self-assessment

**1. What problem does an API Gateway solve?**
It stops clients having to know the platform's internal layout. Without one,
every client hard-codes N addresses, and every cross-cutting concern — auth,
rate limiting, CORS, logging — is implemented N times and drifts. The gateway
gives one address and one place for those concerns.

**2. Direct call vs. through a gateway?**
Direct, the client is coupled to topology: it knows which service owns which
endpoint, and a service moving breaks every client. Through a gateway, the client
knows one address and a URL path; where `/api/orders/**` actually lives becomes a
config line the client never sees. The cost is an extra hop and a component that
must stay up.

**3. What is service discovery trying to solve?**
That a service's address is not stable and not singular. Instances scale, move,
and die; a hand-written URL names one address and cannot express "any healthy
instance". Discovery turns a written-down address into a runtime lookup.

**4. Why does configuration get harder as services multiply?**
Because what grows is the *edges*, not the services. Each new service adds
locations, timeouts, and credentials, and each connection between services adds
another value that has to be right in four environments. Nothing enforces
consistency across files, no single place shows the current state, and secrets
can no longer sit in plain text.

**5. What does data ownership mean?**
Exactly one service is responsible for a piece of data: it holds it, defines its
rules, and is the only thing that writes it. Everyone else asks that service
through its API. Ownership is about authority, not physical storage.

**6. Same DB server — can Order query the User table?**
No. Sharing a server is an operational detail; it does not transfer ownership.
Querying User's table couples us to their schema without their knowledge,
bypasses the rules their code enforces, exposes fields their API deliberately
hides, and makes independent deployment impossible. Nothing about "the tables
happen to be nearby" makes any of that safe.

**7. Why can physical separation come later?**
Because the boundary is enforced in code and credentials, not by network
topology. If no service ever reads another's tables, splitting the database is a
migration. If they do, it's a rewrite — so the discipline has to come first, and
the split can wait for a reason.

**8. What did I learn from putting a gateway in front?**
That adding a component adds a contract. The gateway answered requests that never
reached a backend, and by default it answered them badly — calling a downed
dependency a 500. Routing was the easy part; noticing the gateway had become the
client's whole view of the platform was the actual lesson.

**9. What gets hard going from 2 to 20 services?**
Knowing where everything is, keeping config consistent across environments,
tracing one request across many hops, understanding the blast radius of one
service failing, and stopping teams from taking shortcuts through each other's
data. Debugging changes character: the failure is usually in the space *between*
services rather than inside one.

**10. Draw and explain Client → Gateway → Order → User.**

```
Client ──▶ Gateway ──▶ Order Service ──▶ User Service
       (1)         (2)              (3)
```

1. Client sends `GET /api/orders/100` to the gateway's single address. It knows
   nothing about ports 8081/8082.
2. The gateway matches `Path=/api/orders/**`, resolves the route URI from
   `backend.order-service.url`, and proxies the request. It does not interpret
   the body.
3. Order Service finds order 100 locally, sees `userId=1`, and — because it does
   not own user data — calls `GET /api/users/1`. It composes both into its own
   `OrderResponse` and returns it. The response travels back out through the
   gateway unchanged.

Each hop can fail differently, and each failure has its own code: `NO_ROUTE` and
`BACKEND_UNAVAILABLE` at (2); `ORDER_NOT_FOUND` at (3) before any network call;
`USER_NOT_FOUND` / `USER_SERVICE_UNAVAILABLE` / `USER_SERVICE_TIMEOUT` at the
User Service hop.

## 8. Deliberately not done today

- **Service discovery** — understood, not implemented (guide says so explicitly).
- **Auth, rate limiting, request filtering** — the gateway can host these; today
  it only routes.
- **Payment Service** — the architecture challenge asks for a design, not a build.
- **The gateway is a single point of failure.** One instance, no load balancing.
  Real platforms run several behind a load balancer.
- **No `/actuator/health` anywhere.** Worth adding before anything tries to
  health-check these services.

## 9. Questions for Day 4

- Where does authentication belong — validated once at the gateway, or in every
  service? If the gateway validates, do backends trust a header, and what stops
  someone calling 8081 directly and skipping it?
- Does the `code` taxonomy get shared across services, or stay per-service? Three
  services now define `INVALID_PARAMETER` independently, and the gateway invented
  `BACKEND_UNAVAILABLE` alongside Order Service's `USER_SERVICE_UNAVAILABLE`.
- With the gateway between client and services, how do we trace one request
  across three hops? Each has its own log file and nothing correlates them.
- Retries: safe for `GET /api/users/{id}`, clearly unsafe for `POST /api/payments`.
  Where does that decision belong — gateway, client, or calling service?
