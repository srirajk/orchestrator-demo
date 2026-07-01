from fastapi import FastAPI, HTTPException, Query
from pydantic import BaseModel
from typing import Optional
from data import resolve_entity, check_access
import os

app = FastAPI(title="Meridian Mock CRM", version="1.0.0")

class ResolveRequest(BaseModel):
    query: str
    type: str = "relationship"

@app.get("/health")
def health():
    return {"status": "ok", "service": "mock-crm"}

@app.post("/entities/resolve")
def resolve(req: ResolveRequest, _fail: bool = Query(False, alias="_fail")):
    if _fail:
        raise HTTPException(status_code=503, detail="fault knob triggered")
    return resolve_entity(req.query, req.type)

@app.get("/books/{principal_id}/relationships/{relationship_id}/access")
def access(principal_id: str, relationship_id: str, _fail: bool = Query(False, alias="_fail")):
    if _fail:
        raise HTTPException(status_code=503, detail="fault knob triggered")
    return check_access(principal_id, relationship_id)
