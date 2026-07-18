# Build, Packaging and Deployment Specification

## 1. Purpose

Define exactly how Studio code, UI assets, SDKs, Conduit Packages, JARs, ZIPs and container images
are built and verified. This closes the gap between “the code works” and “the same artifact can be
reproduced, deployed and rolled back without rebuilding unrelated services.”

## 2. Repository constraints discovered

- There is no root Maven aggregator today; gateway, IAM and Chat BFF build independently.
- `gateway/Dockerfile` builds with `maven:3.9-eclipse-temurin-25` and runs on
  `eclipse-temurin:25-jre-alpine`.
- `apps/chat/Dockerfile` already demonstrates the preferred three-stage UI → JAR → runtime pattern.
- Gateway and `registry-service` deliberately use the same gateway image with different Spring
  profiles; registry ingestion is already outside the request-path gateway.
- Root `.gitignore` excludes `build/`, Maven `target/`, Node modules and Playwright results.
- Docker Compose builds the gateway image once logically and assigns it to both gateway and registry
  service; the registry profile owns writes.
- Gateway currently targets Spring Boot 3.5.16. Repository policy says Boot 4 was deferred because
  the JMESPath/Jackson 3 and SSE compatibility path is unresolved. A Boot 4 story therefore starts
  with an explicit compatibility/ADR gate; it is not a blind parent-version edit.

## 3. Maven topology

### 3.1 Aggregation without changing existing parents

Create a root packaging-only `pom.xml` when M1 SDK implementation begins:

```text
orchestrator-demo/pom.xml                 packaging=pom, aggregation only
  libs/conduit-manifest-contracts
  libs/conduit-artifact-sdk
  libs/conduit-admission                  added in M4
  services/onboarding-studio
```

Initially do not add gateway, IAM or Chat BFF as aggregator modules. They retain their current parent,
plugins and independent verification commands. This prevents Studio work from changing existing
runtime dependency management.

Only the integration/build owner edits the root aggregator. Child modules must also build from their
own directory where practical.

### 3.2 Dependency direction

```text
conduit-manifest-contracts
        |
        +--> conduit-artifact-sdk --> onboarding-studio
        |
        +--> conduit-admission --> external registry profile (M4)
        |
        +--> read-only gateway contracts (M4)
```

No SDK imports Spring. No dependency points from an SDK to Studio, ingestion or gateway.

### 3.3 Reproducible Java outputs

All new modules must define:

- Java release/bytecode 21;
- UTF-8 source/reporting encoding;
- pinned plugin versions through the integration-owned build contract;
- `project.build.outputTimestamp` derived from the release source timestamp;
- stable `finalName` values;
- no undeclared system-path/local-repository dependency;
- Maven Enforcer rules for Java/Maven range, dependency convergence and banned dependencies;
- reproducible build comparison on a clean second checkout/build directory.

Required output names:

```text
conduit-manifest-contracts-{version}.jar
conduit-artifact-sdk-{version}.jar
conduit-admission-{version}.jar
conduit-onboarding-studio.jar
```

The Spring Boot executable JAR is the deployment input. The original thin JAR, if produced, is not
published as a runnable image artifact.

## 4. Conduit Package ZIP contract

The canonical artifact is the verified package directory. A downloadable ZIP is a transport wrapper
over exactly that directory.

```text
build/conduit-studio/artifacts/{organization}/{project}/{bundleHash}/
build/conduit-studio/distributions/{bundleHash}.zip
build/conduit-studio/distributions/{bundleHash}.zip.sha256
```

Rules:

1. Package contents are generated once by `conduit-artifact-sdk`; Maven/Docker never regenerate
   business artifacts independently.
2. ZIP entry names are relative, normalized `/` paths sorted lexicographically.
3. Entry timestamps, permissions and compression settings are fixed by compiler policy.
4. ZIP contains no parent directory traversal, symlink, device file, secret or undeclared entry.
5. Unzipping and running `BundleVerifier` produces the same bundle hash as the source directory.
6. The ZIP SHA-256 is transport evidence; the internal `bundleHash` remains the semantic artifact
   identity.
7. Building a Studio image never bakes generated tenant packages into the image.

## 5. Studio container image

### 5.1 Reuse existing build/runtime lineage

Use the existing repository images/stages so Docker can reuse already available base layers. Do not
run with `--pull` during normal local iteration, and do not change `FROM` lines in an application
story.

The initial Studio Dockerfile follows the Chat three-stage pattern:

```dockerfile
FROM node:20-alpine AS web-builder
# npm ci; build apps/onboarding-studio/web

FROM maven:3.9-eclipse-temurin-25 AS jar-builder
# build shared SDKs + services/onboarding-studio with Java 21 bytecode
# copy web dist into classpath:/static before final package

FROM eclipse-temurin:25-jre-alpine AS runtime
# install only the existing healthcheck utility
# copy conduit-onboarding-studio.jar to /app/app.jar
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
```

The exact `FROM` values initially mirror the repository's existing Gateway/Chat choices. A base-image
or digest change is its own supply-chain story with vulnerability, compatibility and rollback proof.

### 5.2 Build context and cache layers

Compose uses repository root as Studio build context so Maven can access shared modules:

```yaml
build:
  context: .
  dockerfile: services/onboarding-studio/Dockerfile
```

Dockerfile copy order:

1. copy package manifests/lockfile and Maven aggregator/module POMs;
2. run `npm ci`/Maven dependency warmup in cacheable layers;
3. copy UI sources and build UI;
4. copy SDK/Studio sources only;
5. build the exact JAR with tests already proven outside the image;
6. copy only the executable JAR into runtime.

Add a root-aware `.dockerignore` for the Studio build excluding `.git`, secrets, environments,
`node_modules`, unrelated service targets, runtime data, evidence, screenshots and generated tenant
packages. It must not exclude the shared SDK source needed by the build.

### 5.3 Image contract

Image name for local/demo:

```text
conduit/onboarding-studio:{version-or-git-sha}
```

Do not rely on `latest` in evidence or promotion receipts. Required OCI labels include revision,
source, created timestamp, application version, contract version and JAR SHA-256. Record image digest,
JAR hash and UI asset manifest hash in deployment evidence.

Runtime rules:

- non-root user;
- read-only root filesystem where compatible;
- writable paths limited to `/tmp` and configured artifact mount;
- no Docker socket;
- no registry-ingestion/gateway write credential;
- JVM memory/container options supplied by environment;
- readiness and liveness endpoints separated;
- graceful shutdown/drain configured and tested;
- SBOM and vulnerability report retained for release images.

## 6. Fast local packaging path

Local iteration must not redownload or rebuild the entire stack.

```text
1. Build only changed Maven modules with `-am`.
2. Build/test only the Studio web package.
3. Package `conduit-onboarding-studio.jar`.
4. Rebuild only the `onboarding-studio` image using Docker cache and `--pull=false` behavior.
5. Recreate only the Studio service.
```

Required commands introduced by implementation:

```text
mvn -pl services/onboarding-studio -am verify
npm --prefix apps/onboarding-studio/web ci
npm --prefix apps/onboarding-studio/web run test
npm --prefix apps/onboarding-studio/web run build
docker compose build onboarding-studio
docker compose up -d --no-deps onboarding-studio
```

Tests are not skipped as a release shortcut. Docker's internal Maven package step may use
`-DskipTests` only when the exact source/JAR inputs have already passed the external Maven gate and
the evidence manifest binds that result.

## 7. Compose topology by milestone

### M2 — Flagship package Studio

Add only:

```text
onboarding-studio
  port: 8096 -> application port
  registry mount: ./registry:/app/registry:ro
  artifact volume: studio_artifacts:/app/artifacts
  dependencies: none on gateway or registry-service
```

M2 may use filesystem/project adapters and generated UI fixtures. Health does not depend on gateway,
Redis, embedding, agents or external ingestion.

### M3 — Durable governance

Studio additionally depends on healthy PostgreSQL and completed MinIO bucket initialization. For the
demo, reuse containers but isolate state:

- separate Studio database/schema and database principal;
- separate MinIO buckets/prefixes and credentials;
- Flyway migration account distinct from runtime account where feasible;
- health readiness verifies database/object-store configuration without exposing secrets;
- named volumes and backup/restore runbook.

Do not give Studio access to gateway Redis/index keys.

### M4 — External ingestion and gateway

Keep the existing `registry-service` external profile/container as the starting mutation boundary.
Refactor its code/image only as required to consume shared SDKs and immutable package inputs. A new
image/deployable requires an ADR showing why the existing profile boundary is insufficient.

Gateway and registry profile may continue sharing a base/runtime image, but profile tests must prove
mutation beans exist only for registry service. Compose retains `gateway depends_on registry-service:
service_healthy` until immutable catalog readiness provides an equivalent or stronger gate.

## 8. Database and object-store delivery

- Flyway migrations are forward-only, checksum-pinned and tested from empty/prior version.
- Destructive migration requires explicit backup/restore/rollback plan; do not hide it in startup.
- Application starts fail closed on schema version mismatch.
- MinIO initialization is idempotent and creates only named Studio buckets/policies.
- Package/evidence objects are content-addressed; database rows reference hashes and object keys.
- Container/image rollback must remain compatible with the last supported database schema.

## 9. Image and artifact harness

Required build assertions:

1. Clean Maven builds in two temporary directories produce identical SDK/JAR hashes or a documented
   non-semantic Boot-layer difference that is eliminated before release.
2. Two package ZIP builds produce identical bytes/hash.
3. Container contains only one intended executable JAR and no source, Maven cache, Node modules,
   `.env`, registry files or tenant package.
4. Container runs as non-root and cannot write outside declared mounts/temp.
5. Health transitions from starting to ready and fails when mandatory M3 dependencies are absent.
6. Image starts with no gateway/registry service available in M2/M3.
7. Rebuilding after a Java source-only change reuses base/dependency/UI layers; rebuilding after a UI
   change reuses Maven dependency/base layers.
8. Image digest/JAR/UI hashes appear in evidence.
9. Rollback to previous image leaves durable project/package state readable.
10. Registry-profile and gateway-profile bean inventories prove write/read separation in M4.

## 10. Supply-chain gate

Release evidence includes:

- dependency lock/BOM and Maven dependency tree;
- npm lockfile and `npm ci` proof;
- JAR/ZIP/image SHA-256 or OCI digest;
- SBOM for Java, Node assets and final image;
- vulnerability scan with severity policy and signed exception expiry;
- base image identity;
- build command/tool versions;
- source revision and dirty-worktree declaration;
- optional signature/attestation when the repository's release infrastructure supports it.

## 11. Definition of done

Build/delivery work is done only when a clean machine can produce the same package/JAR/ZIP, create the
image without pulling unchanged bases during normal iteration, start only the changed service, pass
health/smoke/security checks, record immutable hashes/digests, and roll back the image without losing
or corrupting durable Studio state.

