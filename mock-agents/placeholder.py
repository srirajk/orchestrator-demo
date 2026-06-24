"""
Phase-1 placeholder — returns a minimal 200 OK health response.
Real Wealth HTTP (FastAPI + OpenAPI) and Asset Servicing MCP agents are built in Phase 2.
"""

from fastapi import FastAPI

app = FastAPI(
    title="Mock Agents — Phase 1 Placeholder",
    description="Full Wealth HTTP and Asset Servicing MCP agents arrive in Phase 2.",
    version="0.1.0",
)


@app.get("/health")
def health():
    return {"status": "ok", "phase": "1", "note": "placeholder — real agents in Phase 2"}


@app.get("/")
def root():
    return health()
