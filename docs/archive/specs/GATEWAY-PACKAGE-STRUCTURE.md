# Gateway package structure — the convention of record

> **Binding for all gateway Java.** Every spec that touches `gateway/src/main/java` must state where its new
> classes go, and reviewers must check placement before merge. Root package: `ai.conduit.gateway`.
> This doc describes the structure **as it actually exists today** (verified against the tree), states the
> rules, and flags the known inconsistencies as a *separate* cleanup — not to be folded into feature work.

## The decision: package-by-feature (hexagonal), NOT package-by-layer

Two candidate axes were considered:

| Axis | Shape | Verdict |
|---|---|---|
| **Package-by-feature** (hexagonal / ports-and-adapters) | `domain/chat/`, `domain/auth/`, `adapter/http/`, `infrastructure/telemetry/` | ✅ **ADOPTED** — and already what the codebase does |
| Package-by-layer | `controller.*`, `service.*`, `repository.*`, `cache.*`, `client.*`, `config.*` | ❌ Rejected |

**Why feature-first wins here.** Package-by-layer scatters a single capability across six top-level packages;
it forces every class to be `public` merely to cross a layer boundary (package-private encapsulation becomes
impossible); and a feature can never be seen, reasoned about, or extracted in one place. It only pays off in
small CRUD apps. Feature-first keeps a change localized, permits package-private classes, and matches the
World-B thesis: capabilities are code, **business domains are manifests**.

**Critical World-B clarification.** "Feature" here means a **gateway capability** (chat, intent, clarify,
resolve, synthesize, entitlement, registry, telemetry) — **never a business domain**. There is no `wealth/`
or `insurance/` package and there never will be. Business domains are onboarded via manifest JSON.

## The structure (as it exists)

```
ai.conduit.gateway
├── api/v1/{chat,models,trace,admin,insights}/  all HTTP entry points, versioned
├── domain/                         gateway capabilities, framework-light
│   ├── auth/  chat/  clarify/  coverage/  intent/  manifest/
├── orchestration/{planner,executor,harness,model}/   the DAG engine
├── synthesis/{input,answer}/       input = extract+resolve; answer = synthesize+ground
├── resolver/{service,model}/       routing + agent selection
├── registry/{loader,index,service,introspection,model}/   manifests + vector search
├── adapter/{http,mcp}/             outbound ports — ProtocolAdapter impls
├── infrastructure/                 cross-cutting technical concerns
│   ├── telemetry/ (+ event/)  identity/  metrics/  redis/  web/
└── config/                         Spring @Configuration wiring ONLY
```

## The rules (binding)

1. **A new class goes in the package that OWNS its concern.** Never a flat dumping ground, never "wherever
   was convenient," never a new top-level package without justification in the spec.
2. **Dependency direction:** `api` → `domain` → (ports). **Adapters depend on domain; domain never depends on
   adapters.** `infrastructure` and `adapter` may depend on `domain`; the reverse is forbidden.
3. **`config/` wires, it does not think.** No business or request-path logic in a `@Configuration` class —
   beans, properties, and timeouts only.
4. **`domain/` stays framework-light.** No Spring MVC types, no HTTP, no Jedis in a domain package.
5. **No business-domain names anywhere in a package or class name** (World-B invariant; `world-b-check.sh`).
6. **A `model/` sub-package** holds records/DTOs for that module where the module already uses one
   (`orchestration/model`, `registry/model`, `resolver/model`, `insights/model`). Follow the local convention.
7. **Prefer package-private.** If a class is only used inside its package, do not make it `public`.
8. **Every gateway spec must name the target package for each new class**, and the reviewer checks it.

## Known inconsistencies — RESOLVED (pure package move, no behavior change)

All three were fixed together as their own change (the convention the project already follows for the
public API — controller in `api/v1/*`, logic in `domain/*` — applied consistently). URLs are unchanged;
only Java packages moved.

1. ~~Controllers live in three places.~~ **Fixed.** Every HTTP entry point is now under `api/v1/`:
   `api/v1/{chat,models,trace,admin,insights}`. The one deliberate exception is
   `registry/api/AgentRegistrationController` — it belongs with the registry feature and exists only in
   the `registry` Spring profile, not the gateway.
2. ~~`insights/` is a flat top-level module.~~ **Fixed.** Its logic moved to `domain/insights/` (+ `model/`),
   its controller to `api/v1/insights/` — matching how `domain/chat` + `api/v1/chat` are split.
3. ~~`admin/` is neither versioned nor under `api/`.~~ **Fixed.** Moved to `api/v1/admin/`.

Verified after the move: `mvn` 192/192, world-b CRITICAL 0, and the moved endpoints respond at their
unchanged URLs (`GET /admin/agents`, `/v1/insights/cost`, `/v1/models` → 200; integration 13/13).

## Where the PERF fix's new code goes (mandatory placement)

| Change | Package | Note |
|---|---|---|
| Timed `RestClient` builder bean + timeout properties | `config/` | Wiring only. Inject everywhere; no new package needed. |
| Fix `RemoteEmbeddingClient`'s own `new RestTemplate()` | `registry/index/` (in place) | It builds its own client at `:36` — a bean-only swap misses it. |
| Fix `EntityExtractor` / `EntityResolver` clients | `synthesis/input/` (in place) | They inject the shared bean; swap the bean + verify. |
| `agentRestClient` read timeout | `config/` | Bound by the harness SLA. |
| Async trace persistence (bounded queue + drain workers) | `infrastructure/telemetry/` | Beside `TraceEventPublisher` + `RedisTraceStorageAdapter`. |
| `JedisPooled` pool sizing (`maxTotal`, finite `maxWait`) | `infrastructure/redis/` + properties | Config-driven, never constants. |
| Admission-control policy (Resilience4j bulkhead) | `infrastructure/resilience/` **(new)** | Justified new package: a distinct cross-cutting concern. |
| The admission filter / 503 + `Retry-After` shaping | `infrastructure/web/` | Web-layer concern; must preserve SSE byte-shape. |
| End-to-end deadline | extend the existing per-request context (`domain/auth/RequestContext`) | Do NOT create a parallel request-context concept. |
| Cancellation chain (cancel the pipeline future) | `api/v1/chat/ChatCompletionsController` + `orchestration/executor/` | The future is created at the controller; cancel from emitter callbacks. |

**No new top-level packages** beyond `infrastructure/resilience/`. **No business-domain names.** All timeouts,
pool sizes, deadlines, and the admission ceiling are **config properties** (`@Value`/`application.yml`/env),
never `private static final` constants — per the project's never-hardcode rule.
