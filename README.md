# Microservices

Five small, independently deployable Spring Boot applications talking over
HTTP/REST — no Docker, Kubernetes, or messaging.

| Application        | Port | Owns / does                 | Endpoint                |
|--------------------|------|-----------------------------|-------------------------|
| `discovery-server` | 8761 | Eureka service registry     | dashboard at `/`        |
| `api-gateway`      | 8080 | Client entry point, routing | all client `/api/**`    |
| `user-service`     | 8081 | User information            | `GET /api/users/{id}`   |
| `order-service`    | 8082 | Orders                      | `GET`/`POST /api/orders`|
| `payment-service`  | 8083 | Payments                    | `POST /api/payments`    |

**Nothing addresses anything by URL.** Every service registers with the discovery
server and is reached by logical name — the gateway routes to `lb://USER-SERVICE`,
and Order Service calls `http://USER-SERVICE`. Ports appear in configuration only
so each app knows where to bind itself.

**Clients talk to the gateway on 8080.** They do not need to know the backend
ports exist. The backends stay directly reachable, which is useful for debugging
and for proving the gateway isn't doing anything magic.

**Payment Service is deliberately not routed through the gateway.** It is an
internal service — only Order Service calls it — so `/api/payments/**` returns
`NO_ROUTE` from the front door.

**Each service owns its data.** Order Service stores no users and no payments;
it asks the services that own them. Every service location lives in
configuration, never in Java source.

## Topology

```
                    ┌──────────────────────┐
Client ────────────▶│  API Gateway  :8080  │
                    └──────────┬───────────┘
                     /api/users/**  /api/orders/**
                          │             │
                          ▼             ▼
                 ┌──────────────┐  ┌───────────────┐
                 │ User  :8081  │  │ Order  :8082  │
                 └──────▲───────┘  └───┬───────┬───┘
                        │ GET /api/users/1      │ POST /api/payments
                        └──────────────┘       │ (timeout + retry + breaker)
                                               ▼
                                     ┌──────────────────┐
                                     │ Payment  :8083   │
                                     └──────────────────┘
```

The gateway is the **client's** entry point, not an internal message bus. Order
Service calls User and Payment Service directly — routing internal traffic
through the gateway would add a hop and a single point of failure for calls that
never needed to leave the platform.

## Request flow

```
Client → GET :8080/api/orders/100 → Gateway → Order Service
                                                   │
                                                   │ GET :8081/api/users/1
                                                   ▼
                                              User Service
                                                   │
                                                   ▼
                        Order Service builds combined response
                                                   │
                                    Gateway ◀──────┘ → Client
```

---

## API contract

All three applications return JSON. Every error uses the same envelope, and the
`code` field is the part a calling program should branch on — `message` is for
humans and may be reworded at any time.

```json
{
  "timestamp": "2026-08-14T05:38:07.737Z",
  "status": 503,
  "error": "Service Unavailable",
  "code": "USER_SERVICE_UNAVAILABLE",
  "message": "User Service is not reachable at http://localhost:8081",
  "path": "/api/orders/100"
}
```

### `GET /api/users/{id}` — User Service

| Status | `code`              | When                        |
|--------|---------------------|-----------------------------|
| 200    | —                   | User exists                 |
| 400    | `INVALID_PARAMETER` | `{id}` is not a number      |
| 404    | `USER_NOT_FOUND`    | No user with that id        |

```json
{ "id": 1, "name": "Akshay", "email": "akshay@example.com" }
```

`User` also carries an `internalNote` field. It is **not** in the response — the
DTO is what makes that a deliberate choice rather than an accident.

### `GET /api/orders/{id}` — Order Service

| Status | `code`                     | When                                              |
|--------|----------------------------|---------------------------------------------------|
| 200    | —                          | Order exists and the customer was resolved        |
| 400    | `INVALID_PARAMETER`        | `{id}` is not a number                            |
| 404    | `ORDER_NOT_FOUND`          | This service has no such order                    |
| 404    | `USER_NOT_FOUND`           | Order exists, but its user does not               |
| 502    | `USER_SERVICE_ERROR`       | User Service answered with an error/unusable body |
| 503    | `USER_SERVICE_UNAVAILABLE` | User Service could not be reached                 |
| 504    | `USER_SERVICE_TIMEOUT`     | User Service did not answer in time               |

```json
{
  "orderId": 100,
  "product": "Standing Desk",
  "amount": 2490.00,
  "customer": { "id": 1, "name": "Akshay", "email": "akshay@example.com" }
}
```

Note the two different 404s. Both mean "not found", but `ORDER_NOT_FOUND` is
answered from local state, while `USER_NOT_FOUND` means the order is fine and
a *downstream* lookup came back empty. A consumer that needs to tell those apart
reads `code`, not `status`.

### `POST /api/orders` — Order Service

Validates the customer with User Service, records the order, then charges Payment
Service through a timeout + retry + circuit breaker.

```json
{ "userId": 1, "product": "Monitor", "amount": 450.00 }
```

| Status | `code`              | Order status       | Meaning                              |
|--------|---------------------|--------------------|--------------------------------------|
| 201    | —                   | `PAID`             | Order created and paid               |
| 202    | —                   | `PENDING_PAYMENT`  | Order recorded, payment not completed |
| 400    | `INVALID_REQUEST`   | —                  | Missing or invalid field              |
| 402    | `PAYMENT_DECLINED`  | `PAYMENT_DECLINED` | Payment refused — a final answer      |
| 404    | `USER_NOT_FOUND`    | —                  | No such customer                      |

**202 is not an error.** The order exists and is recorded; the payment did not
complete because Payment Service was slow, down, or being skipped by an open
circuit. The response says so honestly rather than reporting a false success:

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

A **402 decline is different from a 202**: a declined card is a healthy service
giving a final answer, so it is not retried and does not count toward opening the
circuit. The 202 cases mean "we don't know yet".

### Gateway-level errors — API Gateway

These come from the gateway itself, for requests that never reached a backend.
They use the same envelope so a client only has to understand one error shape.

| Status | `code`                | When                                             |
|--------|-----------------------|--------------------------------------------------|
| 404    | `NO_ROUTE`            | No route matches the path (e.g. `/api/payments/`) |
| 503    | `BACKEND_UNAVAILABLE` | The routed service could not be connected to      |
| 504    | `BACKEND_TIMEOUT`     | The routed service accepted but did not reply     |
| 500    | `GATEWAY_ERROR`       | Anything else at the gateway                      |

With User Service stopped, two calls both return 503 — from different components,
for different reasons, and only `code` tells them apart:

```
GET :8080/api/orders/100  → 503 USER_SERVICE_UNAVAILABLE   (order-service said this)
GET :8080/api/users/1     → 503 BACKEND_UNAVAILABLE        (the gateway said this)
```

The first means Order Service is healthy but its dependency isn't. The second
means Order Service's peer is unreachable from the front door.

### How the outbound calls are made

Order Service talks to both dependencies with Spring's synchronous **`RestClient`**
(not `RestTemplate`, not the reactive `WebClient`). One configured client per
dependency, each carrying its own base URL and timeouts:

```java
restClient.post()
        .uri("/api/payments")
        .header("Idempotency-Key", idempotencyKey)
        .body(new PaymentChargeRequest(orderId, amount))
        .retrieve()
        .body(PaymentServicePayment.class);
```

All HTTP lives in `UserClient` and `PaymentClient` — never in a controller. Those
two classes are the only place that knows the other services exist, which is also
where the timeouts, retry, and circuit breaker attach.

All response and request DTOs are records. `Order` is not: it is mutable domain
state, not a data carrier.

### Why there are two user-shaped classes in Order Service

| Class                       | Role                                                     |
|-----------------------------|----------------------------------------------------------|
| `client.UserServiceUser`    | Inbound — mirrors User Service's contract. Theirs to change. |
| `dto.CustomerSummary`       | Outbound — what Order Service promises consumers. Ours.  |

They hold the same three fields today, and that is fine: they are the same shape
by *coincidence*, not by contract. Merging them would mean a rename inside User
Service silently rewrites our public API.

---

## Sample data

Users: `1` Akshay, `2` Pranav, `3` Mazil.

Orders:
- `100` → user `1` (happy path)
- `101` → user `2` (happy path)
- `102` → user `3` (happy path)
- `103` → user `999` — **user does not exist**, demonstrates the downstream-404 case

## Run it (Windows PowerShell)

Set `JAVA_HOME` once per shell, then start each application in its own terminal.
**Start the discovery server first** — the others will start without it, but they
will log connection warnings until it appears.

```bash
$env:JAVA_HOME='C:\Program Files\Java\jdk-25.0.2'; cd C:\Project\microservices\discovery-server; .\mvnw.cmd spring-boot:run
```

Then open **http://localhost:8761** to watch services register.

```bash
$env:JAVA_HOME='C:\Program Files\Java\jdk-25.0.2'; cd C:\Project\microservices\user-service; .\mvnw.cmd spring-boot:run
```

```bash
$env:JAVA_HOME='C:\Program Files\Java\jdk-25.0.2'; cd C:\Project\microservices\order-service; .\mvnw.cmd spring-boot:run
```

```bash
$env:JAVA_HOME='C:\Program Files\Java\jdk-25.0.2'; cd C:\Project\microservices\payment-service; .\mvnw.cmd spring-boot:run
```

```bash
$env:JAVA_HOME='C:\Program Files\Java\jdk-25.0.2'; cd C:\Project\microservices\api-gateway; .\mvnw.cmd spring-boot:run
```

## Try it

Everything through the gateway on 8080 — the client never names 8081 or 8082:

```bash
curl.exe http://localhost:8080/api/users/1       # 200
curl.exe http://localhost:8080/api/users/999     # 404 USER_NOT_FOUND
curl.exe http://localhost:8080/api/users/abc     # 400 INVALID_PARAMETER
curl.exe http://localhost:8080/api/orders/100    # 200 order + customer
curl.exe http://localhost:8080/api/orders/103    # 404 USER_NOT_FOUND (downstream)
curl.exe http://localhost:8080/api/orders/500    # 404 ORDER_NOT_FOUND (local)
curl.exe http://localhost:8080/api/products/1    # 404 NO_ROUTE (gateway itself)
```

Create an order (validates the customer, then charges payment):

```bash
curl.exe -X POST http://localhost:8080/api/orders -H "Content-Type: application/json" -d "{\"userId\":1,\"product\":\"Monitor\",\"amount\":450.00}"
```

## Break it on purpose

Payment Service can be told to misbehave, which is how the resilience behaviour
is demonstrated. These are test-only endpoints.

```bash
curl.exe -X POST "http://localhost:8083/admin/behaviour?mode=SLOW&delayMs=6000"
```

```bash
curl.exe -X POST "http://localhost:8083/admin/behaviour?mode=FAIL"
```

```bash
curl.exe -X POST "http://localhost:8083/admin/behaviour?mode=DECLINE"
```

```bash
curl.exe -X POST "http://localhost:8083/admin/behaviour?mode=OK&failures=1"
```

`GET http://localhost:8083/admin/stats` reports `callsReceived` — the way to
*prove* an open circuit stopped calling Payment Service rather than assume it.
`POST http://localhost:8083/admin/reset` clears counters, modes and stored
payments.

Order Service exposes its breaker at `GET http://localhost:8082/admin/circuit-breaker`
and `POST http://localhost:8082/admin/circuit-breaker/reset`. **Reset the breaker
between experiments** — it remembers failures across requests, and a stale open
circuit makes the next test look broken.

The backends stay directly reachable on 8081/8082 with identical behavior, which
is how you confirm the gateway only routes and does not transform:

```bash
curl.exe http://localhost:8081/api/users/1
curl.exe http://localhost:8082/api/orders/100
```

## Prove the network boundary

Stop User Service, then call Order Service again:

```bash
curl.exe http://localhost:8082/api/orders/100    # 503 USER_SERVICE_UNAVAILABLE
curl.exe http://localhost:8082/api/orders/500    # 404 ORDER_NOT_FOUND - still works
```

The second call still succeeds: Order Service checks what it owns before making
any network call, so an unknown order never depends on User Service being up.
That failure mode does not exist in a monolith's in-process method call.

## Prove service locations are configuration, not code

No recompile — override the property at startup. Order Service's downstream URL:

```bash
java -jar target\order-service-0.0.1-SNAPSHOT.jar --user-service.base-url=http://localhost:9999
```

`GET /api/orders/100` now returns 503 naming port **9999**. The gateway's route
targets work the same way:

```bash
java -jar target\api-gateway-0.0.1-SNAPSHOT.jar --backend.order-service.url=http://localhost:9999
```

`GET :8080/api/orders/100` returns 503 `BACKEND_UNAVAILABLE`. Restart without the
flag to restore. No Java file changes in either case.

## Configuration reference

`order-service`

| Property                          | Default                 | Purpose                           |
|-----------------------------------|-------------------------|-----------------------------------|
| `user-service.base-url`           | `http://localhost:8081` | Where User Service lives          |
| `user-service.connect-timeout-ms` | `2000`                  | Give up if the connection stalls  |
| `user-service.read-timeout-ms`    | `2000`                  | Give up if the answer is too slow |

`order-service` — resilience around the payment call

| Property                                                              | Default | Purpose                                  |
|-----------------------------------------------------------------------|---------|------------------------------------------|
| `payment-service.base-url`                                            | `http://localhost:8083` | Where Payment Service lives |
| `payment-service.read-timeout-ms`                                     | `2000`  | Bound one attempt                        |
| `resilience4j.retry.instances.paymentService.max-attempts`            | `3`     | Total attempts, including the first      |
| `resilience4j.retry.instances.paymentService.retry-exceptions`        | `PaymentUnavailableException` | Only transport failures retry; declines never do |
| `...circuitbreaker.instances.paymentService.sliding-window-size`      | `6`     | How many recent calls are judged         |
| `...circuitbreaker.instances.paymentService.minimum-number-of-calls`  | `4`     | Evidence needed before reacting          |
| `...circuitbreaker.instances.paymentService.failure-rate-threshold`   | `50`    | Percent failures that opens the circuit  |
| `...circuitbreaker.instances.paymentService.wait-duration-in-open-state` | `10s` | How long to stop calling                 |
| `...circuitbreaker.instances.paymentService.record-exceptions`        | `PaymentUnavailableException` | A declined card must not look like an outage |

`api-gateway`

| Property                                            | Default                 | Purpose                        |
|-----------------------------------------------------|-------------------------|--------------------------------|
| `backend.user-service.url`                          | `http://localhost:8081` | Target of the `/api/users/**` route  |
| `backend.order-service.url`                         | `http://localhost:8082` | Target of the `/api/orders/**` route |
| `spring.cloud.gateway.httpclient.connect-timeout`   | `2000` (ms)             | Give up connecting to a backend |
| `spring.cloud.gateway.httpclient.response-timeout`  | `3s`                    | Give up waiting for a backend   |

Without timeouts, a hung backend would hold threads open until they run out —
the outage would spread instead of staying contained.

Note that three service locations already exist across three services.
Configuration grows with the **connections** between services, not the number of
services, which is why it becomes a platform problem well before 20 services.

## Notes

- [DAY2-NOTES.md](DAY2-NOTES.md) — API contracts, DTOs, downstream failure handling
- [DAY3-NOTES.md](DAY3-NOTES.md) — gateway, service discovery, data ownership, architecture
- [DAY4-NOTES.md](DAY4-NOTES.md) — timeouts, retries, circuit breaker, idempotency, fallback (with measurements)
- [DAY5-NOTES.md](DAY5-NOTES.md) — RestClient migration, record DTOs, regression evidence
- [DAY6-NOTES.md](DAY6-NOTES.md) — Eureka discovery, `lb://` routing, what breaks when the registry evicts an instance
