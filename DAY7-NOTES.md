# Day 7 Notes

Day 6 removed hard-coded addresses. Day 7 removes hard-coded *settings* — the
values that differ between dev, test and production.

---

## 1. What was built

A sixth application, **`config-server`** on port 8888, plus a `config-repo/`
folder of property files:

```
config-repo/
├── application.properties          shared by every service
├── user-service.properties         defaults for user-service
├── user-service-dev.properties     dev overrides
├── user-service-test.properties    test overrides
├── order-service.properties
├── order-service-dev.properties
└── order-service-test.properties
```

Both `user-service` and `order-service` import from it:

```properties
spring.config.import=optional:configserver:http://localhost:8888
```

### One deliberate difference from the handbook

The handbook uses the **Git backend** (`spring.cloud.config.server.git.uri=file:///...`).
This project uses the **native (filesystem) backend** instead, so `config-repo/`
lives inside the project and travels with it. A nested Git repository cannot be
cloned along with its parent, so following the handbook literally would produce a
repo that other people can clone but not run.

Switching to the Git backend is one property — the alternative is written into
`config-server/application.properties`. What Git buys you is real: history of who
changed a timeout and when, review before a config change reaches production, and
rollback. That is worth having in a real system; it is not worth breaking the
clone for a learning repo.

### The value that cannot be centralized

```properties
spring.cloud.config.server.native.search-locations=${CONFIG_REPO:file:///C:/Project/microservices/config-repo}
```

Every service now learns its settings from the config server, but the config
server has to be told where the settings live, and *that* cannot come from the
config server. The same is true of `spring.config.import` in each client, and of
`spring.application.name`. **Centralized configuration always leaves a small
bootstrap core behind** — the address of the thing that knows everything else.
It is smaller than what it replaced, which is the point, but it is never zero.

## 2. Profiles — measured

The same `user-service` jar, started three ways, no rebuild:

| Started with | `app.environment` | `app.message` |
|---|---|---|
| *(no profile)* | `DEFAULT` | Hello from centralized config |
| `--spring.profiles.active=dev` | `DEV` | Hello from DEV centralized config |
| `--spring.profiles.active=test` | `TEST` | Hello from TEST centralized config |

The `/admin/config` endpoint lists the resolved property sources, which is where
the mechanism becomes visible. Under `dev`:

```
configserver:.../user-service-dev.properties      ← most specific wins
configserver:.../user-service.properties
configserver:.../application.properties
configClient
Config resource 'class path resource [application.properties]'   ← local, lowest
```

Three layers, most specific first, and the service's own bundled
`application.properties` **last**. That ordering is the whole design: local values
are *defaults*, and anything the config server supplies overrides them.

## 3. Challenge 1 — a real setting, not a demo string

`app.message` proves the wiring but changes no behaviour. The setting actually
moved was the **downstream timeouts** on Order Service.

Local `order-service/application.properties` still says:

```properties
user-service.read-timeout-ms=2000
```

Yet the running service reports:

| Profile | `user-service.read-timeout-ms` | `paymentService.max-attempts` |
|---|---|---|
| *(none)* | **4000** | 3 |
| `dev` | **5000** | 3 |
| `test` | **1000** | **5** |

Same jar, no rebuild, no Java changed. `/admin/config` names the exact file that
won:

```
"winningSource": "configserver:file:/C:/.../config-repo/order-service-dev.properties"
```

The local `2000` was kept on purpose rather than deleted — see the next section
for why that turned out to matter.

## 4. Challenge 3 — Config Server unavailable

Stopped the config server and restarted Order Service two ways.

**A. `optional:configserver:...` (what the project uses)** — service **starts**:

```json
{
  "activeProfiles": ["dev"],
  "app.environment": "LOCAL",
  "user-service.read-timeout-ms": 2000,
  "loadedFromConfigServer": false,
  "winningSource": "Config resource 'class path resource [application.properties]'"
}
```

**B. Same jar, `--spring.config.import=configserver:...` (mandatory)** — service
**refuses to start**:

```
org.springframework.cloud.config.client.ConfigClientFailFastException:
  Could not locate PropertySource and the resource is not optional, failing
```

Both are defensible, and the choice is a real one:

- **Mandatory** fails loudly. You never run with the wrong settings, but the
  config server becomes a hard startup dependency for the whole platform — it
  going down means nothing can be restarted or scaled.
- **Optional** keeps you running. But look at case A again: **the profile was
  `dev` and the service started with `LOCAL` values anyway.** It booted
  successfully, reported itself healthy, and was quietly configured differently
  from what anyone intended. Nothing failed. Nothing alerted.

That silent-wrong-config outcome is the more dangerous of the two, and it is only
survivable because the local defaults are sane. Keeping `2000` in the jar was
what made `optional:` safe — with the value deleted, the same startup would have
failed on a missing placeholder instead.

**The practical rule:** use `optional:` *and* keep working defaults in the
artifact, then monitor `loadedFromConfigServer`. A service running on fallback
config is not an error, but it is a fact somebody needs to see.

## 5. Regression

Config server changed how two services get their settings, so everything from
Days 2–6 was re-run. All identical:

| Check | Result |
|---|---|
| Error matrix via gateway | 200 / 404 / 200 / 404 / 404 / 400 / 404 |
| Healthy order | 201, 1 payment call |
| One blip | 201, 2 calls |
| Always failing | 202, 3 calls |
| Declined | 402, 1 call, breaker CLOSED |
| Breaker 1 / 2 / 3 | 202 in 0.65s / 0.34s / **0.03s**, calls 3 / 4 / **4** |

## 6. The three infrastructure pieces

The handbook warns about confusing these. They answer different questions at
different times:

| | Question | When | If it dies |
|---|---|---|---|
| **Config Server** | *What settings should I use?* | At startup | Running services fine; restarts get fallback config |
| **Eureka** | *Where is USER-SERVICE?* | Continuously | Running services fine (cached); can't learn about changes |
| **Gateway** | *Where should this request go?* | Every client request | **Everything is down from outside** |

Measured across Days 6 and 7: killing Eureka changed nothing, killing the config
server changed nothing until a restart. The gateway is the only one whose loss is
immediately visible to a client — which is exactly backwards from how much
attention each usually gets.

## 7. Secrets

Everything in `config-repo/` is harmless: timeouts, retry counts, a greeting
string. Nothing in there would matter if the repo went public — which it is.

Real configuration eventually includes database passwords, API keys and signing
keys, and those must not be handled this way. A Git-backed config repo is
**append-only history**: a password committed and then removed is still in the
history, still clonable, and rotating it is the only real fix. Spring Cloud Config
supports encrypted values, and Vault or a cloud secret manager is the usual
answer, with the service holding only a token that lets it fetch the real value.

The line worth remembering: **configuration describes how the service should
behave; secrets prove who it is.** Different lifecycle, different audience,
different storage.

## 8. End-of-day questions

**1. Why does configuration get difficult as services grow?**
Because the same value appears in many places. 30 services × 3 environments is 90
files, and a shared value — a broker address, a standard timeout — has to be
changed consistently across all of them by hand. Nothing enforces consistency, and
the failure mode is a subtle mismatch in one environment rather than an obvious
error.

**2. What problem do profiles solve?**
Letting one artifact behave differently per environment. The same jar runs in dev
and prod, so the thing you tested is the thing you shipped, and only the settings
change.

**3. Difference between Eureka and Config Server?**
Eureka answers *where is this service running* — dynamic, changes as instances
come and go, queried continuously. Config Server answers *what settings should
this service use* — deliberate, changed by humans, read at startup. Discovery
tracks reality; configuration expresses intent.

**4. Why separate configuration from business logic?**
Because they change for different reasons and at different rates. Business logic
changes when requirements change and needs a rebuild and a test cycle. A timeout
changes when an environment changes and should need neither. Mixing them means
promoting a build to production requires editing and recompiling it, so the tested
artifact is no longer the shipped one.

**5. What does `spring.application.name` do for Config Server?**
It is the lookup key. The client asks for `{application}/{profile}`, so
`user-service` + `dev` resolves to `user-service-dev.properties`, then
`user-service.properties`, then the shared `application.properties`. Get the name
wrong and you silently receive only the shared defaults.

**6. Why does production need separate secret management?**
Because a config repo is designed to be readable and versioned, and secrets need
the opposite: restricted access, audited reads, rotation, and no permanent
history. Committing a password makes it permanent in the history even after
deletion.

**7. What happens when Config Server is unavailable at startup?**
Measured both ways: with `optional:` the service starts on its bundled defaults
(`loadedFromConfigServer: false`); without it, startup fails with
`ConfigClientFailFastException`. Already-running services are unaffected either
way — configuration is read at startup, not per request.

**8. Why shouldn't a config change be assumed to take effect immediately?**
Because properties are bound at startup. Editing `config-repo` changes what the
*next* start will read; running instances keep the values they booted with. Making
changes live needs `@RefreshScope` plus an explicit `/actuator/refresh`, or a bus
to broadcast it — and even then not everything can be rebound safely.

**9. Which settings would I centralize?**
Service locations, timeouts and retry/breaker settings, log levels, feature flags,
and anything shared across services. Essentially: everything that differs between
environments or that a platform-wide decision should change in one place.

**10. Which settings would I treat as secrets?**
Database credentials, API keys, OAuth client secrets, signing/private keys, and
tokens. The test is whether the value would matter if the repository were public —
and this repository *is* public, which is why nothing of that kind is in it.

## 9. Deliberately not done

- **No `@RefreshScope`.** Config changes require a restart. Live refresh needs
  `spring-boot-starter-actuator` plus `/actuator/refresh` per instance, or Spring
  Cloud Bus to broadcast — worth knowing, not needed to make today's point.
- **No encryption.** All values are harmless, so `{cipher}` and the encrypt/decrypt
  endpoints were skipped.
- **Config Server is not registered with Eureka.** It could be, but clients need
  its address before they have any configuration, so discovery-based lookup would
  be circular unless discovery-first bootstrap is enabled. Keeping it a fixed URL
  is simpler and honest about the bootstrap ordering.
- **Only two services connected.** `payment-service` and `api-gateway` still use
  local configuration only.
- **One Config Server instance** — another single point of failure, mitigated only
  by `optional:` and sane local defaults.

## 10. Questions for Day 8

- If `/actuator/refresh` rebinds a `@Value`, what happens to something built at
  startup from that value — the `RestClient` with its timeouts wouldn't change.
  Which settings can actually be refreshed live?
- With `optional:`, how would anyone notice a service came up on fallback config?
  Is `loadedFromConfigServer` the sort of thing that belongs in a health check?
- Config Server reads the repo per request — does it cache? What happens under a
  restart storm when 30 services all ask at once?
- Where does profile selection itself come from? `--spring.profiles.active=dev` is
  passed at startup, so something outside the platform still decides which
  environment a process thinks it is in.
