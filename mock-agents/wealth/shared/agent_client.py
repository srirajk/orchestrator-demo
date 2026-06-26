"""Z.AI client setup for wealth agents.

Instruments the openai-agents SDK with openinference so every agent call — tool calls,
guardrail runs, LLM messages — is exported as OTel spans to the collector (→ Phoenix + Tempo).

Each agent can override the LLM provider by passing llm_base_url / llm_api_key / llm_model
to make_agent(). Unset params fall back to the service-level WEALTH_AGENT_LLM_* env vars.
"""
import os
import agents
from openai import AsyncOpenAI
from agents import Agent
from openinference.instrumentation.openai_agents import OpenAIAgentsInstrumentor

# Service-level fallback defaults — used when a per-agent override is not set.
LLM_BASE_URL = os.environ.get("WEALTH_AGENT_LLM_BASE_URL", "https://api.openai.com/v1")
LLM_API_KEY  = os.environ.get("WEALTH_AGENT_LLM_API_KEY") or "not-configured"
LLM_MODEL    = os.environ.get("WEALTH_AGENT_LLM_MODEL", "gpt-4o-mini")

agents.set_default_openai_api("chat_completions")

# Route all SDK spans (tool calls, guardrails, LLM calls) through OTel → Phoenix + Tempo.
# This replaces the SDK's default behaviour of POSTing to OpenAI's own trace endpoint,
# which would 401 on a Z.AI key and produce noisy error logs during client demos.
OpenAIAgentsInstrumentor().instrument()


def make_agent(
    name: str,
    instructions: str,
    tools: list,
    input_guardrails: list | None = None,
    output_guardrails: list | None = None,
    llm_base_url: str | None = None,
    llm_api_key: str | None = None,
    llm_model: str | None = None,
) -> Agent:
    """Create a Z.AI-backed Agent with an explicit per-call LLM client.

    Per-agent llm_* params take precedence over the service-level WEALTH_AGENT_LLM_* defaults.
    """
    from agents.models.openai_chatcompletions import OpenAIChatCompletionsModel
    base_url = llm_base_url or LLM_BASE_URL
    api_key  = llm_api_key  or LLM_API_KEY
    model_nm = llm_model    or LLM_MODEL
    client   = AsyncOpenAI(base_url=base_url, api_key=api_key, max_retries=5)
    model    = OpenAIChatCompletionsModel(model=model_nm, openai_client=client)
    return Agent(
        name=name,
        model=model,
        instructions=instructions,
        tools=tools,
        input_guardrails=input_guardrails or [],
        output_guardrails=output_guardrails or [],
    )
