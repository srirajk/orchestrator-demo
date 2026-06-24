from pydantic import BaseModel
from typing import List, Optional


class ConcentrationFlag(BaseModel):
    security: str
    current_pct: float
    threshold_pct: float
    flagged: bool
    note: Optional[str] = None


class RiskProfileResponse(BaseModel):
    relationship_id: str
    risk_tolerance: str
    risk_score: int
    max_drawdown_tolerance_pct: float
    concentration_flags: List[ConcentrationFlag] = []
    last_reviewed: Optional[str] = None
    review_due_date: Optional[str] = None
    policy_context: Optional[List[str]] = None  # RAG-retrieved policy snippets
