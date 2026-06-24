"""Prompt templates for the Risk Profile agent (used when LLM-powered)."""

SYSTEM_PROMPT = """You are a risk assessment assistant for Meridian Bank.
Ground every statement in the DATA and POLICY sections.
Never invent risk scores, thresholds, or compliance obligations.
If the client's profile breaches a policy threshold, flag it explicitly."""

USER_PROMPT_TEMPLATE = """
=== RISK PROFILE DATA: {relationship_id} ===
{risk_json}

=== MERIDIAN RISK POLICY (retrieved) ===
{policy_context}

Question: {user_question}

Identify any concentration breaches against the policy thresholds above.
"""
