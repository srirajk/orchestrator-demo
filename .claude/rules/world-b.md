# World B — non-negotiable invariants for gateway code

The product is World B: the gateway is a **manifest interpreter with zero embedded domain
knowledge**. A new business domain is onboarded by adding manifest JSON + a CRM URL — **never**
by changing gateway Java. The full spec is `docs/WORLD-B-LOCKDOWN.md` (read it before writing
gateway code — §3 is what's currently wrong, §4 is the target, §8 is the definition of done).

## Before writing any code under gateway/src/main/java
- Read `docs/WORLD-B-LOCKDOWN.md` §4 (architecture) and §8 (definition of done).
- The change must not add domain knowledge to the gateway. If you're typing a domain name,
  client name, entity-type literal, ID pattern, or user-facing domain copy into Java — stop;
  it belongs in a manifest.

## The invariants (these never bend)
1. **No domain knowledge in gateway source** — no domain/sub-domain names, no client/entity
   names, no `REL-`/`FND-` patterns, no entity-type literals (`"relationship"`, `"fund"`), no
   user-facing domain copy ("which client?", "in your coverage"). All of these come from the
   effective manifest.
2. **CLARIFY is deterministic, not LLM-judged** — `extracted ∩ required_context = ∅` decided
   in gateway code over manifest-declared required entities, not in a prompt.
3. **Entity context is map-based** — adding an entity type is a manifest edit, not a new Java
   field. No fixed wealth-shaped records.
4. **LLM prompts are compiled from the manifest** — IntentClassifier / EntityExtractor /
   AnswerSynthesizer build their prompts from `entity_types` + `domain_context`; zero
   hardcoded domain strings.
5. **RESOLVE is principal-agnostic; CHECK is the only gate** — never filter entity resolution
   by the principal's book.
6. **Zero fabricated IDs** — the LLM extracts human references; deterministic lookup resolves
   them. Unresolved → clarify, never guess.

## Before declaring any gateway work "done"
- Run `scripts/world-b-check.sh`. It is the deterministic gate.
- Your change must **not increase** the CRITICAL count. A step that claims to remove a
  violation class must drive that class to 0.
- Then self-report against the relevant `docs/WORLD-B-LOCKDOWN.md` §8 checklist items.

## When spawning subagents to write gateway code
Inject the World B agent harness (`docs/WORLD-B-LOCKDOWN.md` §9) into their prompt. Require
them to run `scripts/world-b-check.sh` and report the before/after CRITICAL count.
