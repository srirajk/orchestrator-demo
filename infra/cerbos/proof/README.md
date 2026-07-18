# Cerbos scope-posture — empirical proof harness (Axiom Story B1)

Proves the base=ceiling / tenant=grants posture against the pinned Cerbos runtime.
See `docs/adr/ADR-axiom-scope-posture.md` for the findings and rationale.

## Run it

```bash
bash infra/cerbos/proof/run-proof.sh
```

Uses the pinned `ghcr.io/cerbos/cerbos:0.53.0` image on **distinct** ports
(3600/3601 strict, 3610/3611 lenient contrast) and container names — it never touches the demo's
`conduit-cerbos` (:3594/:3595). Override the image with `CERBOS_IMAGE=…`.

The harness:
1. Prints the Cerbos version.
2. Runs raw `CheckResources` probes for all four load-bearing cases + the lenient-search contrast.
3. Runs the reproducible suite via `cerbos compile --test-output=junit` (11 tests, all pass).

## Layout

- `config.yaml` — proof-server config mirroring the pinned posture (`lenientScopeSearch: false`,
  `decisionLogsEnabled: true`).
- `policies/base_widget.yaml` — the base ceiling (`OVERRIDE_PARENT`): role `user` may `view`+`edit`; `delete` off-ceiling.
- `policies/tenant_acme_widget.yaml` — grants `view`, **silent on `edit`** (fail-through demo).
- `policies/tenant_exceed_widget.yaml` — tries to grant `delete` (ceiling-exceed demo).
- `policies/tenant_boot_widget.yaml` / `tenant_boot2_widget.yaml` — deny-all template before/after a grant.
- `policies/scope_posture_test.yaml` — the `cerbos compile` assertion suite.

## Totality lint

The reusable template lives at `infra/cerbos/templates/tenant-deny-all.yaml`; the lint that enforces
it is `scripts/cerbos-tenant-totality-lint.py`. Pointed at these fixtures the lint flags the two
intentional holes (`acme`/`exceed` silent on `edit`); pointed at `infra/cerbos/policies` it passes
(no tenant scopes yet).
