# Day 5 Notes

Day 5 builds a two-service REST call from scratch. Days 1–4 already built that
call — and a gateway, a payment service, and resilience around it. So today was
mostly a review of work already done, plus the one piece of genuinely new
material in the handbook: **`RestClient`**.

---

## 1. Day 5 checklist against what already existed

| Day 5 requirement | Status before today |
|---|---|
| Two independent Spring Boot services | Done Day 1 (four services by Day 4) |
| Different ports | 8080 / 8081 / 8082 / 8083 |
| User API returning a DTO | `GET /api/users/{id}` → `UserResponse` |
| Order Service calling User Service | `UserClient` since Day 1 |
| Externalized service URL | `user-service.base-url` since Day 1 |
| Successful end-to-end test | Verified every day |
| Test for User Service unavailable | Day 2 §7, returns 503 `USER_SERVICE_UNAVAILABLE` |
| HTTP calls in a client class, not the controller | `UserClient` / `PaymentClient` |
| Controlled error instead of a raw exception | Day 2 error envelope with `code` |
| **Stretch: timeout / retry / circuit breaker / fallback** | **All four, Day 4** |
| **Use `RestClient`** | **No — was `RestTemplate`** |

Only the last row was outstanding, so that is what today actually changed.

## 2. RestTemplate → RestClient

Both clients migrated. The interesting part is how little had to change.

**What changed** — the call itself reads as one fluent chain instead of a method
call with positional arguments:

```java
UserServiceUser user = restClient.get()
        .uri("/api/users/{id}", userId)
        .retrieve()
        .body(UserServiceUser.class);
```

The POST improved more than the GET. `RestTemplate` needed a manual `HttpEntity`
just to attach one header:

```java
HttpHeaders headers = new HttpHeaders();
headers.set("Idempotency-Key", idempotencyKey);
HttpEntity<PaymentChargeRequest> entity = new HttpEntity<>(body, headers);
restTemplate.postForObject(url, entity, PaymentServicePayment.class);
```

`RestClient` states it directly:

```java
restClient.post()
        .uri("/api/payments")
        .header("Idempotency-Key", idempotencyKey)
        .body(new PaymentChargeRequest(orderId, amount))
        .retrieve()
        .body(PaymentServicePayment.class);
```

**What did not change — and this is the point.** `RestClient` throws the *same*
exception hierarchy as `RestTemplate`: `HttpClientErrorException` /
`HttpServerErrorException` for 4xx/5xx, `ResourceAccessException` wrapping
`ConnectException` and `SocketTimeoutException`. So every `catch` block and the
whole `Reason` mapping built on Day 2 survived untouched. Not one status code or
error `code` changed.

Timeouts moved from `RestTemplateBuilder` to an explicit request factory:

```java
SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
factory.setConnectTimeout(connectTimeoutMs);
factory.setReadTimeout(readTimeoutMs);
return builder.baseUrl(baseUrl).requestFactory(factory).build();
```

The base URL now lives on the client, so `uri()` takes a path rather than a full
URL. The `baseUrl` value is still injected into the client class separately,
because the error messages quote it — `"User Service is not reachable at
http://localhost:8081"` is what makes a 503 diagnosable, and it is the string the
Day 2 configuration test relies on.

`RestClient` also replaces `WebClient` for this purpose: it is synchronous, so it
does not drag a reactive stack into a blocking service, and it does not need the
Reactor dependency `WebClient` requires.

## 3. DTOs as records

Day 5 introduces `public record UserResponse(Long id, String name, String email) {}`.
Every pure data carrier across the three services is now a record — eleven
classes, and roughly 250 lines of getters and constructors deleted.

Records work in both directions with no extra configuration: Jackson serializes
via the accessors and deserializes through the canonical constructor, because
`spring-boot-starter-parent` already compiles with `-parameters`. Verified on the
inbound side too — `CreateOrderRequest`, `PaymentRequest`, `UserServiceUser` and
`PaymentServicePayment` are all deserialized from JSON.

`Order` stays an ordinary class. It is not a DTO — it is mutable domain state
whose `status` changes from `PENDING_PAYMENT` to `PAID`, so a record would be the
wrong shape.

Static factories still work on records, which keeps `UserResponse.from(user)`:

```java
public record UserResponse(Long id, String name, String email) {
    public static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getName(), user.getEmail());
    }
}
```

## 4. Regression evidence

The migration touched every outbound call in the system, so the whole Day 4
matrix was re-run. Every number is identical to the pre-migration measurements:

| Scenario | Result | Payment calls | Breaker |
|---|---|---|---|
| Healthy | 201 in 0.04s | 1 | CLOSED |
| Fails once, then recovers | 201 in 0.35s | 2 | CLOSED |
| Always fails | 202 in 0.65s | 3 | CLOSED |
| Declined | 402 in 0.03s | 1 | CLOSED |
| Slow 6s | 201 in 6.03s | 3 calls, **1 capture**, 2 replays | — |
| Breaker run 1 / 2 / 3 | 202 in 0.65s / 0.34s / **0.02s** | 3 / 4 / **4** | CLOSED / OPEN / OPEN |

Reads were byte-for-byte identical, including `non_null` still omitting the null
`message` field on completed payments.

## 5. Where this project deliberately differs from the handbook

Three places the Day 5 reference code is simpler than what is already here. Each
difference is intentional.

**Paths.** The handbook uses `/users/{id}` and `/orders/{orderId}`. This project
uses `/api/users/{id}` and `/api/orders/{id}`, which the gateway routes on
(`Path=/api/users/**`). Renaming them would break the gateway and every documented
contract for no gain.

**Error handling.** The handbook's §7 example is:

```java
catch (Exception ex) {
    throw new RuntimeException("User service is unavailable");
}
```

The handbook itself flags this as intentionally simple. It is worth being explicit
about why it cannot be used here: `catch (Exception)` swallows the difference
between *"that user does not exist"* and *"User Service is down"*. A request for
a genuinely missing user would be reported as an outage — a 503 for something that
should be a 404, and a false statement about the platform's health. That
distinction took most of Day 2 to build. Centralized handling via
`@RestControllerAdvice` is what the handbook recommends two lines later, and it is
what is already in place.

**Returning a String.** The handbook's `OrderController` returns
`"Order 5001 belongs to John"`. Fine for a first call; unusable as a contract,
since a consumer would have to parse prose. This project returns a JSON
`OrderResponse` with a nested `customer`.

## 6. Handbook §14 "common beginner mistakes" — audit

Worth running as a checklist against the actual code rather than assuming.

| Mistake | This project |
|---|---|
| Calling another service's database directly | No shared database at all; every cross-service read is an HTTP call |
| Hard-coding service URLs in business logic | Clean — `grep` for `localhost`/`http://` across `**/*.java` returns nothing |
| HTTP communication inside controllers | All of it is in `UserClient` / `PaymentClient` |
| Retrying without considering safety | Only `PaymentUnavailableException` retries; declines never do, and the charge carries an idempotency key |
| Assuming the downstream is always available | Timeouts, retry, breaker, and a 202 fallback |
| Returning raw internal exceptions | Generic message to the caller, stack trace to the log |
| Testing only the happy path | Day 4 measured five failure modes; one of them found a real triple-charge bug |

## 7. Self-assessment

**1. What is service-to-service communication?**
One service calling another's API over the network to get something it does not
own. The important word is *network*: unlike a method call it can be slow, fail,
or half-succeed, and the caller has to plan for that.

**2. Why call User Service's API instead of its database?**
Because User Service owns that data and its rules live in its code, not its
tables. Reading the table directly couples us to a schema they can change without
telling us, bypasses the rules their API enforces (`internalNote` is a field their
API deliberately withholds), and makes independent deployment impossible.

**3. What does RestClient do?**
It is Spring's synchronous HTTP client — a fluent API over the same machinery
`RestTemplate` used, including the same exception types. It handles building the
request, sending it, and converting the JSON response into a Java type.

**4. What is the purpose of a DTO like `UserResponse`?**
To make the wire shape a deliberate choice instead of a side effect of the
internal model, so persistence changes don't silently become API changes.

**5. Why keep HTTP communication in a client class?**
Because it is the only place that needs to know the other service exists. The URL,
the timeouts, the retry policy, and the translation of every failure mode into a
domain exception all live in one file — so nothing above it handles a
`ResourceAccessException`, and there is a single place to change when the
dependency changes.

**6. What happens when User Service is unavailable?**
`RestClient` throws `ResourceAccessException` wrapping `ConnectException`.
`UserClient` catches it, logs the URL that failed, and throws
`UserServiceException(USER_SERVICE_UNAVAILABLE)`. The advice maps that to **503**
with `code: USER_SERVICE_UNAVAILABLE`. `GET /api/orders/500` still returns 404
throughout, because the local check happens before any network call.

**7. Why externalize service URLs?**
Because the URL describes the environment, not the logic. Otherwise promoting a
build means editing and rebuilding it, and the artifact you tested is no longer
the artifact you shipped.

**8. How does today connect to Day 4?**
Today's client class is exactly where the Day 4 patterns attach. `PaymentClient`
carries `@Retry` and `@CircuitBreaker`, its `RestClient` carries the timeouts, and
`OrderService` supplies the fallback. Resilience patterns wrap the
service-to-service call — which is why the call belongs in its own class rather
than inline in a controller.

**9. What would I test if User Service were slow?**
That the caller gives up at the configured timeout rather than waiting for the
dependency; that the thread is released; that the retry budget doesn't multiply
the delay past what a user will tolerate; and — the one that actually mattered on
Day 4 — that work completing downstream *after* the caller gave up cannot be
double-applied.

**10. What would I test if it were completely down?**
That the response is a deliberate 503 rather than a stack trace; that endpoints
not needing the dependency keep working; that the log names the unreachable URL;
that the breaker opens instead of retrying forever; and that recovery is automatic
once the dependency returns.

## 8. Still outstanding

- **No automated tests.** Every verification so far has been manual curl. The
  `spring-boot-starter-test` dependency is present and unused; `@RestClientTest`
  with `MockRestServiceServer` would pin the `UserClient` failure mapping so a
  future refactor cannot quietly break the 404-vs-503 distinction. That is the
  most valuable next step, and today's migration is exactly the kind of change
  such tests would have de-risked.
- **No reconciliation for `PENDING_PAYMENT` orders** (carried from Day 4).
- **Idempotency claim is in-process only** — needs a durable unique constraint to
  work across instances.
- **No service discovery, no auth between services, no request tracing** — the
  handbook's own "next-step mindset" list.
