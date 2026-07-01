import json
from pathlib import Path

from jsonschema import Draft202012Validator


ROOT = Path(__file__).resolve().parents[2]
REGISTRY = ROOT / "registry"


def load_json(path: Path):
    return json.loads(path.read_text())


def validator(schema_name: str) -> Draft202012Validator:
    schema = load_json(REGISTRY / schema_name)
    Draft202012Validator.check_schema(schema)
    return Draft202012Validator(schema)


def assert_valid(instance, schema_name: str):
    errors = sorted(validator(schema_name).iter_errors(instance), key=lambda e: list(e.path))
    assert not errors, "\n".join(f"{'/'.join(map(str, e.path))}: {e.message}" for e in errors)


def domain_paths():
    return sorted(p for p in (REGISTRY / "domains").glob("*.json") if p.is_file())


def sub_domain_paths():
    return sorted(p for p in (REGISTRY / "domains").glob("*/*.json") if p.is_file())


def agent_paths():
    return sorted((REGISTRY / "manifests").glob("*.json"))


def test_all_registry_schemas_are_valid_json_schema():
    for schema in [
        "agent-manifest.schema.json",
        "domain-manifest.schema.json",
        "sub-domain-manifest.schema.json",
        "context-envelope.schema.json",
        "memory-ledger-event.schema.json",
    ]:
        Draft202012Validator.check_schema(load_json(REGISTRY / schema))


def test_domain_manifests_validate_and_delegate_compaction_to_memory_service():
    for path in domain_paths():
        manifest = load_json(path)
        assert_valid(manifest, "domain-manifest.schema.json")
        memory = manifest["memory_compaction"]
        assert memory["envelope_version"] == "context-envelope.v1"
        assert memory["summary_policy"]["owner"] == "memory-service"
        assert "gateway.response_completed" in memory["summary_policy"]["include_runtime_events"]


def test_sub_domain_manifests_validate():
    for path in sub_domain_paths():
        assert_valid(load_json(path), "sub-domain-manifest.schema.json")


def test_agent_manifests_validate_against_registry_contract():
    for path in agent_paths():
        assert_valid(load_json(path), "agent-manifest.schema.json")


def test_root_agent_schema_matches_registry_contract():
    assert load_json(ROOT / "agent-manifest.schema.json") == load_json(REGISTRY / "agent-manifest.schema.json")


def test_manifest_cross_references_are_consistent():
    domains = {m["domain_id"]: m for m in map(load_json, domain_paths())}
    sub_domains = {m["sub_domain_id"]: m for m in map(load_json, sub_domain_paths())}
    agents = {m["agent_id"]: m for m in map(load_json, agent_paths())}

    for sub_domain in sub_domains.values():
        parent = domains.get(sub_domain["parent_domain"])
        assert parent is not None, f"{sub_domain['sub_domain_id']} references missing parent domain"

        entity_keys = {entity["key"] for entity in sub_domain["entity_types"]}
        for key in sub_domain["required_context"]:
            assert key in entity_keys, f"{sub_domain['sub_domain_id']} required_context {key} has no entity_type"
            assert key in sub_domain.get("clarification_schema", {}), (
                f"{sub_domain['sub_domain_id']} required_context {key} has no clarification_schema entry"
            )

        if sub_domain["resource_scoped"]:
            coverage = parent.get("coverage")
            assert coverage, f"{sub_domain['sub_domain_id']} is resource_scoped without parent coverage"
            assert "{principal_id}" in coverage["discover_url"]
            assert "{principal_id}" in coverage["check_url"]
            assert "{id}" in coverage["check_url"]

        for agent_id in sub_domain["agents"]:
            agent = agents.get(agent_id)
            assert agent is not None, f"{sub_domain['sub_domain_id']} references missing agent {agent_id}"
            assert agent["domain"] == sub_domain["parent_domain"]
            assert agent.get("sub_domain") == sub_domain["sub_domain_id"]

    for agent in agents.values():
        assert agent["domain"] in domains, f"{agent['agent_id']} references missing domain"
        assert agent.get("sub_domain") in sub_domains, f"{agent['agent_id']} references missing sub_domain"


def test_context_envelope_contract_accepts_manifest_driven_domain_keys():
    envelope = {
        "schema_version": "context-envelope.v1",
        "envelope_id": "env_test_1",
        "conversation_id": "conv_test",
        "request_id": "req_test",
        "created_at": "2026-07-01T21:30:00Z",
        "manifest_version": "registry-sha256:test",
        "principal": {
            "principal_id": "rm_jane",
            "tenant_id": "default",
        },
        "scope": {
            "domains": ["wealth-management"],
            "sub_domains": ["private-banking"],
            "agents": ["acme.wealth.holdings"],
        },
        "context": {
            "entities": {
                "relationship_id": {
                    "value": "REL-00042",
                    "display": "Whitman Family Office",
                    "source_event_id": "evt_resolve_1",
                    "observed_at": "2026-07-01T21:28:10Z",
                }
            },
            "literals": {
                "period": {
                    "value": "QTD",
                    "source_event_id": "evt_extract_1",
                    "observed_at": "2026-07-01T21:28:10Z",
                }
            },
            "summaries": [
                {
                    "summary_id": "sum_test_1",
                    "domain": "wealth-management",
                    "sub_domain": "private-banking",
                    "text": "Active context is Whitman Family Office, relationship REL-00042, period QTD.",
                    "token_count": 12,
                    "covers_event_seq": {"from": 1, "to": 12},
                    "created_at": "2026-07-01T21:29:00Z",
                }
            ],
        },
        "authorization_observations": [
            {
                "resource_key": "relationship_id",
                "resource_id": "REL-00042",
                "verdict": "allow",
                "checked_at": "2026-07-01T21:28:11Z",
                "expires_at": "2026-07-01T21:28:41Z",
                "source_event_id": "evt_check_1",
            }
        ],
        "ledger": {
            "last_event_seq": 12,
            "last_compaction_seq": 8,
            "watermark": "ledger:conv_test:12",
        },
    }

    assert_valid(envelope, "context-envelope.schema.json")


def test_memory_ledger_event_contract_accepts_gateway_runtime_event():
    event = {
        "schema_version": "memory-ledger-event.v1",
        "event_id": "evt_check_1",
        "event_seq": 7,
        "conversation_id": "conv_test",
        "request_id": "req_test",
        "type": "gateway.coverage_checked",
        "occurred_at": "2026-07-01T21:28:11Z",
        "source": "conduit-gateway",
        "manifest_refs": {
            "domain": "wealth-management",
            "sub_domain": "private-banking",
            "agent_id": None,
        },
        "payload": {
            "resource_key": "relationship_id",
            "resource_id": "REL-00042",
            "verdict": "allow",
            "expires_at": "2026-07-01T21:28:41Z",
        },
    }

    assert_valid(event, "memory-ledger-event.schema.json")
