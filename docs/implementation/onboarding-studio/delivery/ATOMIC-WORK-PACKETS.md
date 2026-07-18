# Atomic Work Packets

## 1. How to use this file

Assign one row at a time to an implementation model. Each packet must leave the repository runnable
and should fit one focused coding turn. `scripts/studio-check.sh --story <packet>` is the eventual
uniform acceptance command; before that script exists, use the explicit parent-story command/harness.

Ownership codes:

| Code | Owned root |
|---|---|
| C | `contracts/onboarding-studio/` |
| L | `libs/conduit-*/` |
| S | `services/onboarding-studio/` |
| W | `apps/onboarding-studio/web/` |
| E | `tests/studio-e2e/` and Studio-specific test fixtures |
| R | `registry/` and `tests/schema/` — contract owner only |
| D | root `pom.xml`, Dockerfiles, `.dockerignore`, Compose and CI — integration owner only |
| G | `gateway/` and `apps/chat/bff/` — M4 gateway owner only |

No packet may edit an unlisted ownership root.

Every packet owning `W` must also produce browser evidence under `E`: exercised route(s), desktop and
narrow screenshots, console/network result and a repeatable Playwright assertion. This evidence
requirement does not grant the packet ownership of shared Playwright configuration; the E2E owner
handles configuration changes.

## 2. M1 packets

| Packet | Depends | Tier | Owns | Single deliverable |
|---|---|---:|---|---|
| M1-S01-T01 | None | A | C | Read-only inventory command producing normalized domain/sub-domain/agent/skill rows |
| M1-S01-T02 | T01 | A | C | Cross-reference analyzer with the two stable finding codes |
| M1-S01-T03 | T02 | B | C | Frozen `catalog-legacy-broken` inventory/findings/source-hash fixture |
| M1-S02-T01 | S01 | C | C | Identifier, lifecycle, provenance and versioning conventions |
| M1-S02-T02 | T01 | B | C | Business Line/Use Case/capability/route-target JSON Schemas |
| M1-S02-T03 | T01 | B | C | Project/dossier/consent/bundle/finding JSON Schemas |
| M1-S02-T04 | T02,T03 | A | C | Positive and one-negative-per-rule schema fixture suite |
| M1-S03-T01 | S02 | C | D,L | Aggregation-only root POM and two Spring-free module POMs |
| M1-S03-T02 | T01 | B | L | Canonical JSON and SHA-256 APIs with byte-golden tests |
| M1-S03-T03 | T02 | B | L | Safe staged bundle writer/reader/verifier with path-escape tests |
| M1-S04-T01 | S02 | A | S | Filesystem manifest parser mapped to frozen records |
| M1-S04-T02 | T01 | B | S | Forward/reverse catalog graph and provisional route targets |
| M1-S04-T03 | T02 | A/B | S | Health analyzer matching broken fixture findings exactly |
| M1-S05-T01 | S04 | C | C | Read-only Studio OpenAPI operations and problem types |
| M1-S05-T02 | T01 | A | C | OpenAPI-valid real-data API fixtures |
| M1-S05-T03 | T02 | A | C | Generated TypeScript client compilation/hash proof |
| M1-S06-T01 | S01,S04 | C | R | Owner decision record for two clarification definitions |
| M1-S06-T02 | T01 | B | R | Four membership and two clarification manifest repairs |
| M1-S06-T03 | T02 | B | R | Bidirectional cross-reference tests and clean current snapshot |
| M1-S07-T01 | S03–S06 | B | D,E | `studio-check --story/--milestone M1` runner and evidence manifest |
| M1-S07-T02 | T01 | B | E | Planted schema/hash/membership mutants proving the gate fails |

## 3. M2 packets

| Packet | Depends | Tier | Owns | Single deliverable |
|---|---|---:|---|---|
| M2-S01-T01 | G1 | B | S | Boot 4 Studio application/architecture shell with no gateway dependency |
| M2-S01-T02 | T01 | B | S | Project aggregate/state transitions/idempotent command API |
| M2-S01-T03 | T02 | A/B | S | Read/project endpoints matching frozen OpenAPI/problem contracts |
| M2-S02-T01 | G1 | A | W | Vite/React/generated-client project with unit/a11y harness |
| M2-S02-T02 | T01 | A | W | Responsive application shell, tokens, navigation and breadcrumbs |
| M2-S02-T03 | T02 | A | W | Loading/empty/error/assistant-panel primitives |
| M2-S03-T01 | S01,S02 | A | W | Overview counts, health and import-history view |
| M2-S03-T02 | T01 | A | W | Business Line list/detail views |
| M2-S03-T03 | T01 | A | W | Agent Network search/detail/integrity history views |
| M2-S04-T01 | S01,S02 | B | S,W | Project create/type/outcome/owner step vertical slice |
| M2-S04-T02 | T01 | B | S,W | Capability/agent/context/authority selection vertical slice |
| M2-S04-T03 | T02 | B | S,W | Structured-understanding review and material-gap blocking |
| M2-S05-T01 | S04 | B/C | S,W | Signal taxonomy/explanation and collection vertical slice |
| M2-S05-T02 | T01 | C | S,W | Granular consent receipt/withdrawal invariants and UI |
| M2-S05-T03 | T01,T02 | B | S,W | Ownership boundary lab with provenance and generated datasets |
| M2-S06-T01 | G1 | C | S | Frozen dossier-to-artifact mapping table and gap codes |
| M2-S06-T02 | T01 | B | S | Compiler mappings and canonical entry generation |
| M2-S06-T03 | T02 | B | S,L | Atomic folder publication, validation and impact reports |
| M2-S06-T04 | T03 | B/C | E | Renewal-risk exact-byte/hash and negative compiler goldens |
| M2-S07-T01 | S06 | A/B | S | Safe package/artifact read/download API |
| M2-S07-T02 | S02,T01 | A | W | Impact preview and artifact tree |
| M2-S07-T03 | T02 | A/B | W | Synchronized business/plan/proof package reveal |
| M2-S08-T01 | S03–S07 | B | E | Seed/runbook for renewal-risk golden project |
| M2-S08-T02 | T01 | B | E | Isolated Playwright generate-package journey and hash assertions |
| M2-S09-T01 | S08 | B | W,E | Keyboard/screen-reader/automated WCAG pass |
| M2-S09-T02 | S08 | B | E | Desktop/narrow approved visual baselines and failure states |
| M2-S09-T03 | T01,T02 | B | E | Performance/reduced-motion and G2 evidence gate |
| M2-S10-T01 | S08 | C | D | Approved Studio Dockerfile/root build context/.dockerignore |
| M2-S10-T02 | T01 | B | D | Compose Studio service, artifact volume and health check |
| M2-S10-T03 | T02 | B | E,D | Reproducible JAR/image/cache/non-root/targeted-rebuild proof |
| M2-S11-T01 | S01,S02 | C | C,S | Versioned stage/check/transition/invalidation contract |
| M2-S11-T02 | T01 | B/C | S | Server check runner, staleness and derived workflow state |
| M2-S11-T03 | T01,T02 | B | W | WorkflowStepper, ReadinessRail, CheckRow and remediation focus |
| M2-S11-T04 | T03 | A/B | W | Data-driven per-Business-Line workflow/check presentation |
| M2-S11-T05 | T02–T04 | B | E | Browser pass/fail/blocked/stale/warning/unsupported transition suite |

## 4. M3 packets

| Packet | Depends | Tier | Owns | Single deliverable |
|---|---|---:|---|---|
| M3-S01-T01 | G2 | C | S | Approved relational model/Flyway baseline and migration rules |
| M3-S01-T02 | T01 | B | S | Project/dossier/proposal/consent repositories |
| M3-S01-T03 | T01 | B | S | Job/outbox/idempotency repositories and leasing |
| M3-S01-T04 | T02,T03 | B | E | Restart/concurrency/migration/tenant integration proof |
| M3-S02-T01 | G2 | B/C | S | Evidence/object metadata and redaction contract |
| M3-S02-T02 | T01 | B | S | S3/MinIO content-addressed adapter |
| M3-S02-T03 | T02 | B | E | Hash, outage, retention and authorization tests |
| M3-S03-T01 | G2 | C | S,C | Role/resource/permission matrix and API policy contract |
| M3-S03-T02 | T01 | B/C | S,W | OIDC/BFF session and server-side authorization |
| M3-S03-T03 | T02 | C | E | Cross-tenant/non-disclosure/separation adversarial suite |
| M3-S04-T01 | G2 | C | S | Guidance port, typed outputs and allowed-tool contract |
| M3-S04-T02 | T01 | B | S | Provider adapter, context builder, limits and fallback |
| M3-S04-T03 | T02 | B/C | E | Injection/outage/privacy/cost evaluation suite |
| M3-S05-T01 | S02,S03 | C | S | Inspection target/consent/egress policy |
| M3-S05-T02 | T01 | B | S | HTTP/OpenAPI bounded inspection adapter |
| M3-S05-T03 | T01 | B | S | MCP bounded inspection adapter |
| M3-S05-T04 | T02,T03 | B/C | E | SSRF/redirect/credential/timeout/malformed-contract proof |
| M3-S06-T01 | S02,S04 | C | S | Ownership-neighbor and dataset separation contract |
| M3-S06-T02 | T01 | B | S,W | Ownership lab/regression implementation |
| M3-S06-T03 | T01 | B/C | S,W | Capability-plan/composition validation and visualization |
| M3-S06-T04 | T02,T03 | B | E | Poaching/cycle/schema/map/fan-in negative suite |
| M3-S07-T01 | S01,S02,S05,S06 | C | S | Gate taxonomy/input hash/verdict truth table |
| M3-S07-T02 | T01 | B/C | S | Certification runner, staleness and evidence persistence |
| M3-S07-T03 | T02 | C | E | Planted hard-failure/NOT_RUN/unsupported verdict suite |
| M3-S08-T01 | S03,S07 | C | S,C | Approval/separation/invalidation contract |
| M3-S08-T02 | T01 | B/C | S,W | Approval commands/inbox/diff/audit UI |
| M3-S08-T03 | T02 | C | E | Stale/concurrent/self-approval/audit reconstruction suite |
| M3-S09-T01 | S01–S08 | B | E | Knowledge/resource/composition positive scenarios |
| M3-S09-T02 | S01–S08 | B/C | E | Overlap/insufficient/injection/cross-tenant/outage scenarios |
| M3-S09-T03 | T01,T02 | B | E | Restart/idempotency and complete G3 evidence manifest |
| M3-S10-T01 | S01,S02 | C | D | Isolated Studio database/schema/principal and MinIO bucket policy |
| M3-S10-T02 | T01 | B | D,E | Compose dependency/health/migration/init wiring |
| M3-S10-T03 | T02 | B/C | E,D | Backup/restore and prior-image/forward-schema rollback proof |

## 5. M4 packets

| Packet | Depends | Tier | Owns | Single deliverable |
|---|---|---:|---|---|
| M4-S01-T01 | G3 | C | G,L | Characterization fixtures/results for current admission semantics |
| M4-S01-T02 | T01 | B/C | L,G | Spring-free admission extraction with unchanged results |
| M4-S01-T03 | T02 | C | E,G | Parity, architecture and World-B acceptance evidence |
| M4-S02-T01 | S01 | C | G,D | Current registry-profile bean/credential/Compose boundary characterization |
| M4-S02-T02 | T01 | B/C | G,L | Registry profile consumes package/SDK through frozen ports |
| M4-S02-T03 | T02 | C | E,G | Service identity, write separation and architecture proof |
| M4-S03-T01 | S02 | C | G | Candidate dry-run command/API and exact-hash checks |
| M4-S03-T02 | T01 | B/C | G | Bounded HTTP/MCP introspection adapters |
| M4-S03-T03 | T01,T02 | C | E,G | Non-mutation/idempotency/security receipt proof |
| M4-S04-T01 | S03 | C | G | Immutable catalog materializer under one catalog hash |
| M4-S04-T02 | T01 | C | G | Version-isolated shadow index builder/readiness |
| M4-S04-T03 | T02 | C | E,G | Completeness/isolation/regression/failure-injection suite |
| M4-S05-T01 | S04 | C | G | Compare-and-set active pointer and immutable receipt |
| M4-S05-T02 | T01 | C | G | Retention, acknowledgment and rollback implementation |
| M4-S05-T03 | T02 | C | E,G | Concurrent activation/idempotency/rollback suite |
| M4-S06-T01 | S01 | C | G | Boot 4/Jackson/JMESPath/SSE compatibility spike and ADR |
| M4-S06-T02 | T01 approved | C | G,D | Isolated dependency/configuration migration |
| M4-S06-T03 | T02 | C | E,G | Unit/integration/SSE/World-B/dependency parity gate |
| M4-S07-T01 | S05,S06 | C | G | Read-only immutable catalog store/capture |
| M4-S07-T02 | T01 | C | G | One-snapshot threading through request path |
| M4-S07-T03 | T01 | B/C | G | BFF conversation pin/drain/expiry behavior |
| M4-S07-T04 | T02,T03 | C | E,G | Mixed-version, tenant, authz, coverage and readiness suite |
| M4-S08-T01 | S07 | C | E,D,G | Sandbox activation/serve/rollback seed and runbook |
| M4-S08-T02 | T01 | C | E | One-hash end-to-end and cross-tenant browser/API proof |
| M4-S08-T03 | T02 | C | E,D | Full release evidence, rollback rehearsal and G4 signoff |
| M4-S09-T01 | S08 | C | D | Release image/JAR/UI/package digest and SBOM manifest |
| M4-S09-T02 | T01 | C | D,E | Registry/gateway profile image/bean/credential parity proof |
| M4-S09-T03 | T02 | C | D,E | Prior-image and prior-catalog production-like rollback proof |

## 6. Packet definition of done

Each packet is done only when its one deliverable exists, owned-path diff is clean, parent acceptance
criteria impacted by the packet pass, negative cases pass, exact commands/evidence are reported, and
the master marks the next dependency `READY`. Multiple packets must not be collapsed into one cheap
model prompt merely because they share a story.
