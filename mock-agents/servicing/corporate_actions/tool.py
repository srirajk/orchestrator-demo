"""Corporate Actions MCP tool — RAG-augmented."""
import json
from shared.canned_data import CORPORATE_ACTIONS
from shared.fault_knobs import maybe_fault
from shared.telemetry import agent_span
from .knowledge_base.retriever import retrieve

AGENT_ID = "acme.servicing.corporate_actions"


def get_corporate_actions(relationship_id: str) -> str:
    """Get upcoming corporate actions with regulatory context for a relationship."""
    with agent_span(AGENT_ID, relationship_id) as span:
        maybe_fault("get_corporate_actions")
        data = CORPORATE_ACTIONS.get(relationship_id)
        if data is None:
            span.set_attribute("error", True)
            return json.dumps({"error": f"Relationship '{relationship_id}' not found."})

        # RAG: retrieve relevant corp action processing rules
        actions = data.get("upcoming_actions", [])
        action_types = " ".join(a.get("type", "") for a in actions)
        query = f"corporate action {action_types} processing election"
        regulatory_snippets = retrieve(query, top_k=2)

        data_with_context = dict(data)
        data_with_context["regulatory_context"] = regulatory_snippets

        span.set_attribute("result.action_count", len(actions))
        span.set_attribute("rag.retrieved_docs", len(regulatory_snippets))
        return json.dumps(data_with_context)
