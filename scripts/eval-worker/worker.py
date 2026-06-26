"""
Meridian continuous eval worker — golden-dataset driver + DeepEval scorer.

Architecture:
  1. Seeds golden datasets into Langfuse on startup.
  2. Every POLL_INTERVAL seconds, picks a golden question, calls the live
     gateway, parses the SSE response, and evaluates the answer with DeepEval
     (Faithfulness, AnswerRelevancy, Hallucination) using Z.AI GLM as judge.
  3. Creates a Langfuse trace + posts scores — open :3030 to see live results.
"""

import os
import time
import logging
import json
import random
import uuid
import httpx
from datetime import datetime, timezone

from langfuse import Langfuse
from deepeval.metrics import FaithfulnessMetric, AnswerRelevancyMetric, HallucinationMetric
from deepeval.test_case import LLMTestCase
from deepeval.models import DeepEvalBaseLLM

from golden_datasets import GOLDEN_DATASETS

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [eval-worker] %(levelname)s %(message)s",
)
log = logging.getLogger(__name__)

# ── Config ────────────────────────────────────────────────────────────────────

LANGFUSE_PUBLIC_KEY = os.environ["LANGFUSE_PUBLIC_KEY"]
LANGFUSE_SECRET_KEY = os.environ["LANGFUSE_SECRET_KEY"]
LANGFUSE_HOST       = os.environ.get("LANGFUSE_HOST", "http://langfuse:3000")
GATEWAY_HOST        = os.environ.get("GATEWAY_HOST", "http://gateway:8080")
ZAI_API_KEY         = os.environ["ZAI_API_KEY"]
ZAI_BASE_URL        = os.environ.get("ZAI_BASE_URL", "https://api.z.ai/api/paas/v4")
ZAI_EVAL_MODEL      = os.environ.get("ZAI_EVAL_MODEL", "glm-4.6")
POLL_INTERVAL       = int(os.environ.get("EVAL_POLL_INTERVAL_SECONDS", "60"))
USER_MGMT_HOST      = os.environ.get("USER_MGMT_HOST", "http://user-mgmt:8084")
EVAL_USER_ID        = "rm_jane"
EVAL_USER_PASS      = "rm_jane"

_cached_token: str | None = None
_token_expiry: float = 0.0


def get_bearer_token() -> str:
    global _cached_token, _token_expiry
    if _cached_token and time.time() < _token_expiry - 60:
        return _cached_token
    try:
        resp = httpx.post(
            f"{USER_MGMT_HOST}/auth/token",
            json={"user_id": EVAL_USER_ID},
            timeout=10,
        )
        data = resp.json()
        _cached_token = data["access_token"]
        _token_expiry = time.time() + data.get("expires_in", 3600)
        log.info("Fetched JWT for %s (expires in %ds)", EVAL_USER_ID, data.get("expires_in", 3600))
        return _cached_token
    except Exception as e:
        log.warning("Failed to fetch JWT: %s — proceeding without auth", e)
        return "no-token"

SCORE_FAITHFULNESS  = "faithfulness"
SCORE_RELEVANCY     = "answer_relevancy"
SCORE_HALLUCINATION = "hallucination"


# ── Z.AI judge ────────────────────────────────────────────────────────────────

class ZAIJudge(DeepEvalBaseLLM):
    def __init__(self):
        from openai import OpenAI
        self._client = OpenAI(base_url=ZAI_BASE_URL, api_key=ZAI_API_KEY)

    def get_model_name(self):
        return ZAI_EVAL_MODEL

    def load_model(self):
        return self._client

    def generate(self, prompt: str) -> str:
        resp = self._client.chat.completions.create(
            model=ZAI_EVAL_MODEL,
            messages=[{"role": "user", "content": prompt}],
            temperature=0,
        )
        return resp.choices[0].message.content

    async def a_generate(self, prompt: str) -> str:
        return self.generate(prompt)


# ── Langfuse dataset seeding ──────────────────────────────────────────────────

def seed_datasets(lf: Langfuse) -> None:
    dataset_names = {e["dataset"] for e in GOLDEN_DATASETS}
    for ds_name in dataset_names:
        try:
            lf.create_dataset(name=ds_name, description=f"Meridian golden eval set: {ds_name}")
            log.info("Created dataset: %s", ds_name)
        except Exception as e:
            if "already exists" not in str(e).lower():
                log.debug("Dataset %s: %s", ds_name, e)

    for entry in GOLDEN_DATASETS:
        try:
            lf.create_dataset_item(
                dataset_name=entry["dataset"],
                input={"question": entry["input"]},
                expected_output={"key_facts": entry["expected"], "agents": entry["agents"]},
                metadata={"name": entry["name"], "context": entry["context"]},
            )
        except Exception as e:
            if "already exists" not in str(e).lower():
                log.debug("Dataset item %s: %s", entry["name"], e)

    log.info("Seeded %d golden items across %d datasets", len(GOLDEN_DATASETS), len(dataset_names))


# ── Gateway caller ────────────────────────────────────────────────────────────

def call_gateway(question: str, conversation_id: str) -> str | None:
    """Call the live gateway and collect the full streamed answer."""
    payload = {
        "model": "meridian",
        "messages": [{"role": "user", "content": question}],
        "stream": True,
    }
    headers = {
        "Content-Type": "application/json",
        "Authorization": f"Bearer {get_bearer_token()}",
        "X-Conversation-Id": conversation_id,
        "X-User-Id": EVAL_USER_ID,
    }
    collected = []
    try:
        with httpx.stream(
            "POST",
            f"{GATEWAY_HOST}/v1/chat/completions",
            json=payload,
            headers=headers,
            timeout=120,
        ) as resp:
            if resp.status_code != 200:
                log.warning("Gateway returned %d", resp.status_code)
                return None
            for line in resp.iter_lines():
                if not line or not line.startswith("data:"):
                    continue
                data = line[5:].strip()
                if data == "[DONE]":
                    break
                try:
                    chunk = json.loads(data)
                    delta = chunk.get("choices", [{}])[0].get("delta", {})
                    content = delta.get("content", "")
                    if content:
                        collected.append(content)
                except Exception:
                    pass
    except Exception as e:
        log.warning("Gateway call failed: %s", e)
        return None

    answer = "".join(collected).strip()
    return answer if len(answer) > 20 else None


# ── Evaluation ────────────────────────────────────────────────────────────────

def run_eval(lf: Langfuse, judge: ZAIJudge, entry: dict, answer: str, conv_id: str) -> None:
    question  = entry["input"]
    context   = entry["context"]
    trace_id  = str(uuid.uuid4())
    session   = f"eval-{entry['name']}"

    log.info("  Scoring faithfulness / relevancy / hallucination ...")
    scores: dict[str, float] = {}

    try:
        tc = LLMTestCase(input=question, actual_output=answer, retrieval_context=[context])
        m = FaithfulnessMetric(threshold=0.7, model=judge, include_reason=True)
        m.measure(tc)
        scores[SCORE_FAITHFULNESS] = round(m.score, 3)
        log.info("    faithfulness=%.3f", m.score)
    except Exception as e:
        log.warning("    faithfulness failed: %s", e)

    try:
        tc = LLMTestCase(input=question, actual_output=answer)
        m = AnswerRelevancyMetric(threshold=0.7, model=judge, include_reason=True)
        m.measure(tc)
        scores[SCORE_RELEVANCY] = round(m.score, 3)
        log.info("    relevancy=%.3f", m.score)
    except Exception as e:
        log.warning("    relevancy failed: %s", e)

    try:
        tc = LLMTestCase(input=question, actual_output=answer, context=[context])
        m = HallucinationMetric(threshold=0.3, model=judge, include_reason=True)
        m.measure(tc)
        scores[SCORE_HALLUCINATION] = round(m.score, 3)
        log.info("    hallucination=%.3f", m.score)
    except Exception as e:
        log.warning("    hallucination failed: %s", e)

    # Upsert the trace in Langfuse
    try:
        lf.trace(
            id=trace_id,
            name=f"eval:{entry['name']}",
            input={"question": question},
            output={"answer": answer},
            session_id=session,
            user_id=EVAL_USER_ID,
            metadata={
                "golden_entry": entry["name"],
                "dataset": entry["dataset"],
                "expected_agents": entry["agents"],
                "conversation_id": conv_id,
            },
        )
    except Exception as e:
        log.warning("  Langfuse trace create failed: %s", e)
        return

    # Post scores
    for name, value in scores.items():
        try:
            lf.score(
                trace_id=trace_id,
                name=name,
                value=value,
                comment=f"Z.AI {ZAI_EVAL_MODEL} via DeepEval",
            )
        except Exception as e:
            log.warning("  Score post failed %s: %s", name, e)

    if scores:
        log.info("  ✓ Langfuse trace %s | scores: %s", trace_id[:8], scores)
    else:
        log.warning("  No scores produced for %s", entry["name"])


# ── Main loop ─────────────────────────────────────────────────────────────────

def wait_for_langfuse(lf: Langfuse, retries: int = 20) -> bool:
    for i in range(retries):
        try:
            lf.auth_check()
            log.info("Langfuse auth OK")
            return True
        except Exception as e:
            log.info("Waiting for Langfuse (%d/%d): %s", i + 1, retries, e)
            time.sleep(10)
    return False


def wait_for_gateway(retries: int = 30) -> bool:
    for i in range(retries):
        try:
            r = httpx.get(f"{GATEWAY_HOST}/v1/models", timeout=5)
            if r.status_code == 200:
                log.info("Gateway ready")
                return True
        except Exception:
            pass
        log.info("Waiting for gateway (%d/%d)", i + 1, retries)
        time.sleep(10)
    return False


def main():
    log.info(
        "Eval worker starting — gateway=%s Langfuse=%s Z.AI=%s poll=%ds",
        GATEWAY_HOST, LANGFUSE_HOST, ZAI_EVAL_MODEL, POLL_INTERVAL,
    )

    lf = Langfuse(
        public_key=LANGFUSE_PUBLIC_KEY,
        secret_key=LANGFUSE_SECRET_KEY,
        host=LANGFUSE_HOST,
    )

    if not wait_for_langfuse(lf):
        log.error("Langfuse never became ready — exiting")
        return

    if not wait_for_gateway():
        log.error("Gateway never became ready — exiting")
        return

    try:
        seed_datasets(lf)
    except Exception as e:
        log.warning("Dataset seeding failed (non-fatal): %s", e)

    judge = ZAIJudge()
    log.info("Z.AI judge initialised (%s)", ZAI_EVAL_MODEL)
    log.info("Starting continuous eval loop every %ds", POLL_INTERVAL)

    # Round-robin through golden entries
    entry_index = 0
    entries = list(GOLDEN_DATASETS)

    while True:
        try:
            entry = entries[entry_index % len(entries)]
            entry_index += 1

            conv_id = f"eval-{entry['name']}-{int(time.time())}"
            log.info("→ Evaluating: %s", entry["name"])
            log.info("  Q: %s", entry["input"][:80])

            answer = call_gateway(entry["input"], conv_id)
            if not answer:
                log.warning("  Gateway returned no answer — skipping")
            else:
                log.info("  A: %s...", answer[:120])
                run_eval(lf, judge, entry, answer, conv_id)

        except Exception as e:
            log.error("Eval loop error: %s", e)

        time.sleep(POLL_INTERVAL)


if __name__ == "__main__":
    main()
