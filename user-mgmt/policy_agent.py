"""
Policy Agent — generates Cerbos YAML from structured intent + system context.

The LLM prompt is built from:
  1. All roles currently in Redis (so it never hallucates role names)
  2. All existing Cerbos policy files (conflict detection + style matching)
  3. The resource attribute schemas for all known resource types
  4. The structured intent from the admin (resource, subject_roles, actions, conditions)

Output is a JSON envelope: {yaml, explanation, warnings}
"""

import json
import os
import pathlib
from typing import Any

import yaml

CERBOS_POLICIES_DIR = os.getenv("CERBOS_POLICIES_DIR", "/cerbos-policies")
LLM_BASE_URL = os.getenv("OPENAI_BASE_URL", "https://api.openai.com/v1")
LLM_API_KEY = os.getenv("OPENAI_API_KEY", "")
LLM_MODEL = os.getenv("LLM_MODEL", "gpt-4o-mini")

# ── Resource schemas — what attributes exist on each resource type ─────────────

RESOURCE_SCHEMAS: dict[str, dict] = {
    "agent": {
        "actions": ["invoke", "register", "deregister"],
        "principal_attrs": {
            "clearance": "int (1-5)",
            "segments": "list[str] — e.g. ['wealth', 'servicing']",
            "domains": "list[str] — org domain memberships",
            "admin_domains": "list[str] — domains this user administers",
        },
        "resource_attrs": {
            "domain": "str — 'wealth-management' | 'asset-servicing'",
            "is_mutating": "bool — true if the agent writes data",
            "data_classification": "str — 'confidential-pii' | 'confidential' | 'internal'",
        },
        "cel_examples": [
            "!R.attr.is_mutating",
            "int(P.attr.clearance) >= 2",
            "P.attr.segments.exists(s, s == \"wealth\")",
            "R.attr.domain == \"wealth-management\"",
            "P.attr.admin_domains.exists(d, d == \"wealth-private-banking\")",
            "(R.attr.data_classification != \"confidential-pii\" || int(P.attr.clearance) >= 2)",
        ],
    },
    "relationship": {
        "actions": ["read", "update", "delete"],
        "principal_attrs": {
            "clearance": "int (1-5)",
            "segments": "list[str]",
        },
        "resource_attrs": {
            "classification": "str — 'confidential' | 'internal'",
            "owner_team": "str — team that owns this relationship",
        },
        "cel_examples": [
            "int(P.attr.clearance) >= 2",
            "P.attr.segments.exists(s, s == \"wealth\")",
        ],
    },
    "domain": {
        "actions": ["read", "admin", "add_member", "remove_member"],
        "principal_attrs": {
            "admin_domains": "list[str]",
            "clearance": "int (1-5)",
        },
        "resource_attrs": {
            "domain_id": "str",
        },
        "cel_examples": [
            "P.attr.admin_domains.exists(d, d == R.attr.domain_id)",
        ],
    },
}

_SYSTEM_PROMPT = """\
You are a Cerbos authorization policy expert for Meridian Bank's AI gateway.
Generate a valid Cerbos resource policy YAML based on the structured intent provided.

CERBOS POLICY FORMAT:
```yaml
apiVersion: api.cerbos.dev/v1
resourcePolicy:
  version: "default"
  resource: "<resource>"
  rules:
    - actions: ["<action>"]
      effect: EFFECT_ALLOW   # or EFFECT_DENY
      roles:
        - "<role_id>"
      condition:             # optional
        match:
          expr: "<CEL expression>"
```

RULES:
- Output JSON with keys: yaml (string), explanation (2-3 sentences), warnings (list)
- yaml field: raw YAML text only — no markdown fences
- explanation: plain English — who can do what, under what conditions
- warnings: list of conflicts with existing policies, or [] if none
- Use only role IDs from the EXISTING ROLES list below
- Use only attributes listed in the RESOURCE SCHEMA below
- Match the CEL style of EXISTING POLICIES
- If conditions dict is empty, omit the condition block entirely
"""


def _load_existing_policies() -> list[dict]:
    """Read all YAML files from the Cerbos policies directory."""
    policies = []
    d = pathlib.Path(CERBOS_POLICIES_DIR)
    if not d.exists():
        return policies
    for f in sorted(d.glob("*.yaml")):
        try:
            content = f.read_text()
            doc = yaml.safe_load(content)
            policies.append({"filename": f.name, "content": content, "parsed": doc})
        except Exception:
            pass
    return policies


def _detect_conflicts(intent: dict, existing: list[dict]) -> list[str]:
    """Simple conflict detection: same resource + overlapping roles + overlapping actions."""
    warnings = []
    resource = intent.get("resource", "")
    subject_roles = set(intent.get("subject_roles", []))
    actions = set(intent.get("actions", []))

    for policy in existing:
        doc = policy.get("parsed") or {}
        rp = doc.get("resourcePolicy", {}) if isinstance(doc, dict) else {}
        if rp.get("resource") != resource:
            continue
        for rule in rp.get("rules", []):
            rule_roles = set(rule.get("roles", []))
            rule_actions = set(rule.get("actions", []))
            overlap_roles = subject_roles & rule_roles
            overlap_actions = actions & rule_actions
            if overlap_roles and overlap_actions:
                warnings.append(
                    f"Overlap with {policy['filename']}: roles {sorted(overlap_roles)} "
                    f"already have rules for actions {sorted(overlap_actions)} on '{resource}'. "
                    f"Review before applying to avoid conflicts."
                )
    return warnings


def generate_policy(
    intent: dict,
    existing_roles: list[dict],
    redis_client: Any = None,
) -> dict:
    """
    Generate a Cerbos policy from structured intent.

    intent keys:
      resource        str   — "agent" | "relationship" | "domain" | custom
      subject_roles   list  — role IDs from the system
      actions         list  — actions to allow
      conditions      dict  — {clearance_min, segments, non_mutating_only, custom_cel}
      policy_name     str   — filename stem (no .yaml)
      description     str   — human intent description

    Returns: {yaml, explanation, warnings, valid, errors}
    """
    try:
        from openai import OpenAI
    except ImportError:
        return {
            "yaml": "",
            "explanation": "",
            "warnings": [],
            "valid": False,
            "errors": ["openai package not installed in user-mgmt container"],
        }

    resource = intent.get("resource", "agent")
    subject_roles = intent.get("subject_roles", [])
    actions = intent.get("actions", [])
    conditions = intent.get("conditions", {})
    description = intent.get("description", "")

    schema = RESOURCE_SCHEMAS.get(resource, RESOURCE_SCHEMAS["agent"])
    existing_policies = _load_existing_policies()
    conflicts = _detect_conflicts(intent, existing_policies)

    # Build condition hints for the LLM
    condition_lines = []
    if conditions.get("clearance_min"):
        condition_lines.append(f"- clearance >= {conditions['clearance_min']}")
    if conditions.get("segments"):
        for seg in conditions["segments"]:
            condition_lines.append(f"- principal must be in segment '{seg}'")
    if conditions.get("non_mutating_only"):
        condition_lines.append("- resource must be non-mutating (read-only)")
    if conditions.get("domain"):
        condition_lines.append(f"- resource domain must be '{conditions['domain']}'")
    if conditions.get("custom_cel"):
        condition_lines.append(f"- custom CEL: {conditions['custom_cel']}")

    existing_policy_text = "\n\n".join(
        f"--- {p['filename']} ---\n{p['content']}" for p in existing_policies
    ) or "(none)"

    role_list = "\n".join(
        f"- {r['id']}: {r.get('name', '')} — {r.get('description', '')}"
        for r in existing_roles
    ) or "(no roles defined yet)"

    user_message = f"""EXISTING ROLES IN SYSTEM:
{role_list}

RESOURCE: {resource}
AVAILABLE ACTIONS: {schema['actions']}
PRINCIPAL ATTRIBUTES: {json.dumps(schema['principal_attrs'], indent=2)}
RESOURCE ATTRIBUTES: {json.dumps(schema['resource_attrs'], indent=2)}
CEL EXAMPLES FROM THIS SYSTEM:
{chr(10).join(schema['cel_examples'])}

EXISTING POLICIES (check for conflicts):
{existing_policy_text}

ADMIN INTENT:
- Subject roles: {subject_roles}
- Actions to allow: {actions}
- Conditions:
{chr(10).join(condition_lines) if condition_lines else '  (none — allow unconditionally for these roles)'}
- Description: {description}

Generate the Cerbos policy JSON response now."""

    client = OpenAI(api_key=LLM_API_KEY, base_url=LLM_BASE_URL)
    response = client.chat.completions.create(
        model=LLM_MODEL,
        messages=[
            {"role": "system", "content": _SYSTEM_PROMPT},
            {"role": "user", "content": user_message},
        ],
        temperature=0.1,
        response_format={"type": "json_object"},
    )

    raw = response.choices[0].message.content or "{}"
    result = json.loads(raw)

    yaml_str = result.get("yaml", "")
    validation = validate_policy_yaml(yaml_str)

    return {
        "yaml": yaml_str,
        "explanation": result.get("explanation", ""),
        "warnings": conflicts + result.get("warnings", []),
        "valid": validation["valid"],
        "errors": validation["errors"],
    }


def validate_policy_yaml(yaml_str: str) -> dict:
    """
    Multi-layer validation:
    1. YAML parse
    2. Required Cerbos fields
    3. Rule structure (actions, effect)
    """
    errors: list[str] = []

    if not yaml_str or not yaml_str.strip():
        return {"valid": False, "errors": ["Empty policy"], "parsed": None}

    try:
        doc = yaml.safe_load(yaml_str)
    except yaml.YAMLError as e:
        return {"valid": False, "errors": [f"YAML syntax error: {e}"], "parsed": None}

    if not isinstance(doc, dict):
        return {"valid": False, "errors": ["Policy must be a YAML mapping"], "parsed": None}

    if doc.get("apiVersion") != "api.cerbos.dev/v1":
        errors.append("apiVersion must be 'api.cerbos.dev/v1'")

    policy_type = next(
        (k for k in ("resourcePolicy", "principalPolicy", "derivedRoles", "exportVariables")
         if k in doc),
        None,
    )

    if not policy_type:
        errors.append("Missing policy block (resourcePolicy, principalPolicy, etc.)")
    else:
        policy = doc[policy_type]
        if not isinstance(policy, dict):
            errors.append(f"{policy_type} must be a mapping")
        else:
            if "version" not in policy:
                errors.append(f"{policy_type}.version is required")
            if policy_type == "resourcePolicy" and "resource" not in policy:
                errors.append("resourcePolicy.resource is required")
            for i, rule in enumerate(policy.get("rules", [])):
                if "actions" not in rule:
                    errors.append(f"Rule {i}: 'actions' required")
                if "effect" not in rule:
                    errors.append(f"Rule {i}: 'effect' required")
                elif rule["effect"] not in ("EFFECT_ALLOW", "EFFECT_DENY"):
                    errors.append(f"Rule {i}: effect must be EFFECT_ALLOW or EFFECT_DENY")
            if not policy.get("rules"):
                errors.append(f"{policy_type}.rules must have at least one rule")

    return {"valid": len(errors) == 0, "errors": errors, "parsed": doc}


def apply_policy(yaml_str: str, filename: str) -> dict:
    """Write a validated policy to the Cerbos policies directory."""
    d = pathlib.Path(CERBOS_POLICIES_DIR)
    if not d.exists():
        return {"applied": False, "error": f"Policies directory not found: {CERBOS_POLICIES_DIR}"}

    safe = filename.replace("/", "_").replace("..", "_").replace(" ", "_")
    if not safe.endswith(".yaml"):
        safe += ".yaml"

    target = d / safe
    target.write_text(yaml_str)
    return {"applied": True, "path": str(target), "filename": safe}


def list_policies() -> list[dict]:
    """List all policy files in the Cerbos directory."""
    d = pathlib.Path(CERBOS_POLICIES_DIR)
    if not d.exists():
        return []
    result = []
    for f in sorted(d.glob("*.yaml")):
        try:
            content = f.read_text()
            doc = yaml.safe_load(content) or {}
            policy_type = next(
                (k for k in ("resourcePolicy", "principalPolicy", "derivedRoles")
                 if k in doc), "unknown"
            )
            resource = doc.get(policy_type, {}).get("resource", "—") if policy_type != "unknown" else "—"
            result.append({
                "filename": f.name,
                "policy_type": policy_type,
                "resource": resource,
                "size": f.stat().st_size,
                "content": content,
            })
        except Exception as exc:
            result.append({"filename": f.name, "error": str(exc)})
    return result
