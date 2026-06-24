from pydantic import BaseModel
from typing import List, Optional


class CorporateAction(BaseModel):
    action_id: str
    security: str
    isin: Optional[str] = None
    type: str
    record_date: Optional[str] = None
    payment_date: Optional[str] = None
    effective_date: Optional[str] = None
    election_deadline: Optional[str] = None
    election_required: bool = False
    amount_per_share: Optional[float] = None
    total_amount: Optional[float] = None
    ratio: Optional[str] = None
    price: Optional[float] = None
    notes: Optional[str] = None


class CorporateActionsResponse(BaseModel):
    relationship_id: str
    upcoming_actions: List[CorporateAction]
    as_of_date: str
    regulatory_context: Optional[List[str]] = None  # RAG-retrieved rules
