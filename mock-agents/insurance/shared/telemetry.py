"""
OTel + OpenInference span helper for the Insurance HTTP service.

Each agent wraps its work in agent_span() to produce a child span that:
  1. Shows in Tempo/Grafana (via OTel Collector)
  2. Appears in Langfuse as an AGENT span with input/output captured
     (Langfuse reads OpenInference semantic conventions on OTel spans)

Distributed tracing:
  setup_telemetry(app) wires FastAPIInstrumentor so that every inbound
  request from the gateway automatically reads the W3C traceparent header
  and creates a child span under the gateway's root span. This produces
  the full Tempo trace: gateway root → agent child span.
"""
import contextlib
import logging
import os

from opentelemetry import trace
from opentelemetry.baggage import get_baggage
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import BatchSpanProcessor
from opentelemetry.exporter.otlp.proto.http.trace_exporter import OTLPSpanExporter

try:
    from opentelemetry.instrumentation.fastapi import FastAPIInstrumentor
    _FASTAPI_INSTRUMENTOR_AVAILABLE = True
except ImportError:
    _FASTAPI_INSTRUMENTOR_AVAILABLE = False

try:
    from openinference.semconv.trace import SpanAttributes, OpenInferenceSpanKindValues
    _OI_AVAILABLE = True
except ImportError:
    _OI_AVAILABLE = False

log = logging.getLogger(__name__)

OTEL_ENDPOINT = os.getenv("OTEL_EXPORTER_OTLP_ENDPOINT", "http://otel-collector:4318")
SERVICE_NAME = os.getenv("OTEL_SERVICE_NAME", "conduit-insurance-http")

provider = TracerProvider()
exporter = OTLPSpanExporter(endpoint=f"{OTEL_ENDPOINT}/v1/traces")
provider.add_span_processor(BatchSpanProcessor(exporter))
trace.set_tracer_provider(provider)
tracer = trace.get_tracer(SERVICE_NAME)


def setup_telemetry(app) -> None:
    """
    Wire FastAPIInstrumentor to the FastAPI app.

    Call this once after ``app = FastAPI()``. FastAPIInstrumentor then:
      - Reads the W3C ``traceparent`` header on every inbound request.
      - Creates a child span automatically linked to the gateway's root span.
      - Exports to the OTel Collector → Tempo, completing the distributed trace.

    If the instrumentation package is missing the service starts normally but
    agent spans will not be linked to the gateway trace (logged as a warning).
    """
    if not _FASTAPI_INSTRUMENTOR_AVAILABLE:
        log.warning(
            "opentelemetry-instrumentation-fastapi is not installed; "
            "agent spans will NOT be linked to the gateway traceparent. "
            "Add 'opentelemetry-instrumentation-fastapi' to requirements.txt."
        )
        return
    FastAPIInstrumentor.instrument_app(app)
    log.info(
        "FastAPIInstrumentor active — OTel endpoint: %s service: %s",
        OTEL_ENDPOINT,
        SERVICE_NAME,
    )


def agent_span(agent_id: str, entity_id: str = None, input_value: str = None):
    """
    Context manager that creates an OpenInference AGENT span.

    Langfuse reads SpanAttributes.OPENINFERENCE_SPAN_KIND = "AGENT" to group
    agent invocations in session traces.

    Usage:
        with agent_span("meridian.insurance.policy_details", entity_id="POL-77001",
                        input_value=policy_id) as span:
            span.set_attribute("result.status", "active")
            ...
    """
    @contextlib.contextmanager
    def _span():
        with tracer.start_as_current_span(f"agent.{agent_id.split('.')[-1]}") as span:
            span.set_attribute("agent.id", agent_id)
            span.set_attribute("agent.protocol", "http")
            span.set_attribute("agent.domain", "insurance")
            if entity_id:
                span.set_attribute("entity.id", entity_id)
            # OpenInference semantic conventions — Langfuse reads these
            if _OI_AVAILABLE:
                span.set_attribute(
                    SpanAttributes.OPENINFERENCE_SPAN_KIND,
                    OpenInferenceSpanKindValues.AGENT.value,
                )
                span.set_attribute(SpanAttributes.INPUT_VALUE, input_value or entity_id or "")
            # Structured logging — emit traceId and convId for downstream correlation
            ctx = span.get_span_context()
            tid = format(ctx.trace_id, "032x") if ctx and ctx.is_valid else ""
            conv_id = get_baggage("convId") or ""
            log.info(
                "agent_span_start",
                extra={"traceId": tid, "convId": conv_id, "agent": agent_id},
            )
            yield span

    return _span()
