"""
OTel + OpenInference span helper for the Wealth HTTP service.

Each agent wraps its work in agent_span() to produce a child span that:
  1. Shows in Tempo/Grafana (via OTel Collector)
  2. Appears in Arize Phoenix as an AGENT span with input/output captured
     (Phoenix uses OpenInference semantic conventions to detect agent spans)
"""
import contextlib
import os

from opentelemetry import trace
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import BatchSpanProcessor
from opentelemetry.exporter.otlp.proto.http.trace_exporter import OTLPSpanExporter

try:
    from openinference.semconv.trace import SpanAttributes, OpenInferenceSpanKindValues
    _OI_AVAILABLE = True
except ImportError:
    _OI_AVAILABLE = False

OTEL_ENDPOINT = os.getenv("OTEL_EXPORTER_OTLP_ENDPOINT", "http://otel-collector:4318")
SERVICE_NAME = os.getenv("OTEL_SERVICE_NAME", "meridian-wealth-http")

provider = TracerProvider()
exporter = OTLPSpanExporter(endpoint=f"{OTEL_ENDPOINT}/v1/traces")
provider.add_span_processor(BatchSpanProcessor(exporter))
trace.set_tracer_provider(provider)
tracer = trace.get_tracer(SERVICE_NAME)


def agent_span(agent_id: str, relationship_id: str = None, input_value: str = None):
    """
    Context manager that creates an OpenInference AGENT span.

    Phoenix uses SpanAttributes.OPENINFERENCE_SPAN_KIND = "AGENT" to display
    the span in the agent experiments panel.

    Usage:
        with agent_span("acme.wealth.holdings", relationship_id="REL-00042",
                        input_value=relationship_id) as span:
            span.set_attribute("result.position_count", n)
            ...set output via span.set_attribute("output.value", json_str)...
    """
    @contextlib.contextmanager
    def _span():
        with tracer.start_as_current_span(f"agent.{agent_id.split('.')[-1]}") as span:
            span.set_attribute("agent.id", agent_id)
            span.set_attribute("agent.protocol", "http")
            span.set_attribute("agent.domain", "wealth")
            if relationship_id:
                span.set_attribute("entity.relationship_id", relationship_id)
            # OpenInference semantic conventions — Phoenix reads these
            if _OI_AVAILABLE:
                span.set_attribute(
                    SpanAttributes.OPENINFERENCE_SPAN_KIND,
                    OpenInferenceSpanKindValues.AGENT.value,
                )
                span.set_attribute(SpanAttributes.INPUT_VALUE, input_value or relationship_id or "")
            yield span

    return _span()
