# Coding-Agent Execution Contract

## 1. Purpose

This contract lets multiple coding agents implement bounded stories without drifting shared
contracts or overwriting one another. It applies to every child story in this delivery package.

## 2. Required work packet

Every assigned story must provide:

```text
Story ID and objective:
Milestone/gate:
Dependencies already green:
Contract/schema hashes:
Owned files/directories:
Read-only references:
Forbidden edits:
Fixtures and expected hashes:
Build/package/image impact:
Approved base image and build context:
Functional requirements:
Negative/security requirements:
Acceptance criteria:
Commands to run:
Required evidence paths:
Migration/rollback expectation:
Cache/reproducibility expectation:
Integration reviewer:
Stop conditions:
```

If any field is missing, the agent may inspect and report but must not invent a material contract.

## 3. Ownership rules

- One agent owns a file at a time.
- Only the contract owner edits shared schemas, OpenAPI, root build aggregation and generated clients.
- Frontend agents consume generated clients/fixtures; they do not create private response types.
- Backend agents implement published contracts; they do not rewrite UX vocabulary.
- Harness agents may add tests/evidence tooling but do not approve their own golden business facts.
- Integration owners merge shared changes and run the milestone gate.
- Only the build/integration owner edits root Maven aggregation, Docker `FROM` lines, root-aware
  `.dockerignore`, shared Compose topology or CI release configuration.
- Every user-visible UI packet is browser-in-the-loop: the owner must load and exercise the running
  route in a controlled browser, check console/network/accessibility/responsive behavior, retain
  screenshot/trace evidence and add a repeatable Playwright assertion.
- Agents preserve unrelated dirty-worktree changes and stop on overlapping edits they cannot isolate.

## 4. Story execution loop

```text
Orient -> verify dependencies -> run baseline -> implement smallest vertical behavior
-> run narrow harness -> inspect diff -> run integration harness -> produce evidence
-> hand off for review -> integration owner runs milestone gate
```

Agents must lead with observable behavior. Scaffolding without an executable acceptance path does not
complete a story.

## 5. Stop conditions

Stop and report rather than guessing when:

- a required contract/schema is absent or internally inconsistent;
- the current repository contradicts the story baseline;
- a proposed change would widen activation, secret or tenant authority;
- a model result is being treated as approved fact;
- an expected golden hash changes outside the story's declared inputs;
- the story requires editing files owned by another active lane;
- an ingestion mutation is required before M4;
- a gateway runtime change is required before M4;
- a packet would change a Docker base image, root build context, shared Compose service or release
  plugin without the build/integration owner;
- a Docker build unexpectedly pulls/replaces an approved base or includes secrets/generated tenant
  packages in its context/image;
- a hard security/tenant test cannot run or is skipped unexpectedly.
- a user-visible change cannot be loaded and exercised in the real browser harness.

## 6. Handoff report

Every story ends with:

```text
Story:
Outcome delivered:
Files changed:
Contracts consumed/changed:
Acceptance criteria result:
Tests and exact results:
Tests not run and reason:
Evidence paths and hashes:
Browser routes exercised, screenshot/trace paths and console/network result or “not applicable”:
JAR/ZIP/image/UI hashes or “not applicable”:
Docker cache/base/build-context result or “not applicable”:
Known limitations/risks:
Rollback/migration note:
Next unblocked stories:
```

Do not use “all tests passed” without naming the commands and counts.

## 7. Review levels

| Change | Required review |
|---|---|
| UI-only against frozen fixtures | Frontend + accessibility |
| Schema/OpenAPI/shared SDK | Contract owner + all consumer representatives |
| Compiler/canonicalization/hash | Compiler + independent fixture owner |
| Authorization/consent/approval | Security + product authority |
| Model prompt/tool boundary | Model safety + application owner |
| External ingestion/activation | Platform + security + operations |
| Gateway runtime/Spring migration | Gateway owner + regression/security |
| Maven aggregation/Docker/Compose/base image | Build + supply-chain + affected service owner |

## 8. Parallel-agent rules

At most four implementation lanes run concurrently. Each lane must own disjoint files. Parallel
stories communicate only through frozen contracts and fixture hashes. When a contract changes, all
affected lanes stop at their next safe boundary until the integration owner republishes the contract.

## 9. Agent-story definition of done

A coding agent may call a story complete only when the behavior exists, all story acceptance
criteria and negative cases are proven, required evidence is present, its diff contains no
out-of-scope edits, and the integration reviewer can reproduce the result from the handoff alone.
