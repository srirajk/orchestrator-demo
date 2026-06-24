"""
Fault-knob support for MCP tools via environment variables.

  MCP_FAULT_TOOL=get_settlements   → makes that tool return an error
  MCP_FAULT_DELAY_MS=500           → delays every tool call
  MCP_FAULT_ALL=true               → all tools fail (broad outage test)
"""
import os
import time


def maybe_fault(tool_name: str) -> None:
    """Raise RuntimeError if the fault knob is set for this tool."""
    fault_all = os.getenv("MCP_FAULT_ALL", "").lower() in ("true", "1")
    fault_tool = os.getenv("MCP_FAULT_TOOL", "")
    delay_ms = int(os.getenv("MCP_FAULT_DELAY_MS", "0") or 0)

    if delay_ms > 0:
        time.sleep(delay_ms / 1000)

    if fault_all or fault_tool == tool_name:
        raise RuntimeError(
            f"fault knob triggered for '{tool_name}' "
            f"(MCP_FAULT_TOOL={fault_tool!r}, MCP_FAULT_ALL={fault_all})"
        )
