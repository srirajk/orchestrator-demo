"""
OTel + OpenInference span helper for the Asset Servicing MCP service.

Each MCP tool wraps its work in agent_span() to produce an OpenInference AGENT span
visible in both Tempo/Grafana and Langfuse.
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
SERVICE_NAME = os.getenv("OTEL_SERVICE_NAME", "meridian-servicing-mcp")

provider = TracerProvider()
exporter = OTLPSpanExporter(endpoint=f"{OTEL_ENDPOINT}/v1/traces")
provider.add_span_processor(BatchSpanProcessor(exporter))
trace.set_tracer_provider(provider)
tracer = trace.get_tracer(SERVICE_NAME)


def agent_span(agent_id: str, entity_id: str = None, entity_type: str = "relationship",
               input_value: str = None):
    """
    Context manager for an OpenInference AGENT span (MCP tool invocation).

    Langfuse displays these in session traces with input/output and latency.
    """
    @contextlib.contextmanager
    def _span():
        with tracer.start_as_current_span(f"agent.{agent_id.split('.')[-1]}") as span:
            span.set_attribute("agent.id", agent_id)
            span.set_attribute("agent.protocol", "mcp")
            span.set_attribute("agent.domain", "servicing")
            if entity_id:
                span.set_attribute(f"entity.{entity_type}_id", entity_id)
            if _OI_AVAILABLE:
                span.set_attribute(
                    SpanAttributes.OPENINFERENCE_SPAN_KIND,
                    OpenInferenceSpanKindValues.AGENT.value,
                )
                span.set_attribute(SpanAttributes.INPUT_VALUE, input_value or entity_id or "")
            yield span

    return _span()
