# Conduit Onboarding Studio — Manifest Compiler Specification

**Status:** Proposed  
**Nature:** Deterministic build component; zero model calls  
**Input:** Confirmed, approved capability dossier  
**Output:** Canonical candidate bundle or typed compilation gaps

---

## 1. Purpose

Teams never author Conduit manifests. The compiler translates a human-readable, approved dossier
into the exact domain, sub-domain, agent, eval and policy-proposal artifacts required by the current
platform contract.

The compiler is not an agent. It performs explicit, versioned field mappings and refuses missing or
unsupported meaning.

---

## 2. Compiler API

```text
compile(
  dossierSnapshot,
  dossierSchemaVersion,
  targetManifestSchemaVersions,
  compilerVersion,
  targetEnvironmentPolicy
) -> CandidateBundle | CompilationFailure
```

No network, registry write, LLM, secret lookup or mutable global state is permitted during compile.

---

## 3. Preconditions

- Dossier version is immutable and hash verified.
- All required facts for scope/archetype exist.
- Material facts have required approvals.
- Referenced evidence exists and is authorized.
- Protocol/archetype is supported by target compiler version.
- Target schema versions are installed and hash verified.
- No unresolved ownership or governance conflict remains.

Failure returns path-specific gaps; it never inserts guessed values.

---

## 4. Candidate bundle

```text
bundle.json                         bundle metadata/hashes
manifests/agents/<agent-id>.json   agent manifest
domains/<domain-id>.json           only for new/changed domain
domains/<domain>/<subdomain>.json  only for new/changed sub-domain
eval/submitted.json                submitted evidence rows
eval/adversarial.json              Studio-generated/reviewed rows
eval/held-out.refs.json            references/hashes, not exposed answers
policy/proposal.yaml               optional, never auto-applied
provenance.json                    artifact path -> dossier facts/evidence/compiler rule
contract.md                        human-readable capability contract
limitations.json                   approved limitations
```

All JSON uses canonical ordering/serialization. Bundle hash covers every file path and content hash.

---

## 5. Mapping rules

### Agent identity

Map approved ID, name, description, semantic version, provider, domain, sub-domain and owners. Owners
not supported by the runtime manifest live in bundle metadata/provenance, not illegal schema fields.

### Protocol connection

Compile only observed and confirmed protocol/operation/tool connection facts. Credential references
never enter the manifest. A2A remains unsupported until target registry support exists.

### Skills and routing examples

Compile approved capability description, tags and positive examples. Maintain example provenance in
the sidecar. Do not put negative/confuser cases into `skills[].examples`; those belong to eval assets.

### Constraints

Compile access mode, classification, SLA and rate limits from approved facts/policy. V1 rejects
write access mode as unsupported even if the current schema can represent it.

### Entity and required context

Compile confirmed entity keys, extraction labels, kinds, display names, identifier patterns,
resolution types, required/default semantics and clarification policy into the sub-domain manifest.
Required context must reference declared entity keys.

### Coverage

Compile only approved URL templates and cache policy. Resource-scoped sub-domains require DISCOVER,
CHECK and RESOLVE contracts. Secrets never enter URLs/artifacts.

### Semantic dataflow

Compile approved entity leaves, `consumes.from`, projections, `produces` types/entities/figures,
condition and map declarations. Symbolic types must be stable and namespace-scoped.

### User-facing copy

Generate copy from approved meaning/tone templates, then require owner confirmation before dossier
confirmation. Compiler only inserts already approved strings or versioned domain-neutral defaults.

### Domain policy

Compile domain context, clarification policy and required governed-memory fields according to current
schema. If the current schema requires a field that the supported Studio product cannot truthfully
derive/approve, return a schema/product gap rather than boilerplate.

---

## 6. Provenance sidecar

Each material artifact path maps to:

```json
{
  "artifact": "manifests/agents/example.json",
  "jsonPointer": "/constraints/data_classification",
  "dossierPaths": ["governance.dataClassification"],
  "provenance": "DECLARED",
  "evidenceIds": ["answer-91"],
  "approvalIds": ["approval-security-8"],
  "compilerRule": "agent.constraints.classification.v1"
}
```

Compiler defaults use `DEFAULTED` plus rule/config hash. No artifact field lacks a mapping.

---

## 7. Validation sequence

1. Dossier schema and approvals.
2. Scope/archetype support.
3. Internal fact consistency.
4. Artifact mapping completeness.
5. Canonical serialization.
6. JSON Schema validation against all relevant current schemas.
7. Cross-file referential integrity.
8. Dataset schema/provenance validation.
9. Bundle/provenance completeness.
10. Hash generation and reproducibility check.

Live introspection, routing and authorization are certification responsibilities, not compilation.

---

## 8. Compilation gaps

Stable codes include:

- `MISSING_REQUIRED_FACT`
- `MISSING_REQUIRED_APPROVAL`
- `CONTRADICTORY_FACTS`
- `UNSUPPORTED_PROTOCOL`
- `UNSUPPORTED_ACCESS_MODE`
- `UNSUPPORTED_ARCHETYPE_FEATURE`
- `INVALID_ENTITY_CONTRACT`
- `INVALID_DATAFLOW_CONTRACT`
- `INVALID_USER_COPY`
- `SCHEMA_VALIDATION_FAILED`
- `REFERENTIAL_INTEGRITY_FAILED`
- `PROVENANCE_INCOMPLETE`
- `NONDETERMINISTIC_OUTPUT`

Every gap includes dossier paths, owner role and remediation.

---

## 9. Versioning

- Compiler semantic version is pinned in bundle metadata.
- Mapping rules have stable IDs/versions.
- Schema hashes are pinned.
- A compiler upgrade never mutates an old bundle.
- Recompile produces a new bundle and readable diff.
- Migration tests prove old dossier versions either migrate deterministically or fail with a typed
  unsupported-version gap.

---

## 10. Tests

- Golden compilation for all three archetypes.
- Byte-identical repeated compilation.
- Randomized map/set insertion order produces same output.
- Missing fact/approval failures.
- Unsupported A2A/write requirements.
- Current three schema copies remain compatible.
- Cross-file membership/reference validation.
- Provenance coverage for every material path.
- Generated/submitted/held-out dataset separation.
- No credential material in output.
- Compiler process performs no network/model calls.

---

## 11. Acceptance criteria

- Domain team never edits generated JSON/YAML.
- Same approved inputs generate identical bundle hash.
- Every field is explainable from dossier/evidence/default.
- Unsupported meaning fails explicitly.
- Bundle passes current schema validation before certification.
- Compiler has no authority to register or promote.
