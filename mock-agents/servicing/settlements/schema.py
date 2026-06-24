from pydantic import BaseModel
from typing import List, Optional


class SettlementTrade(BaseModel):
    trade_id: str
    security: str
    isin: Optional[str] = None
    side: str
    amount: float
    settle_date: Optional[str] = None
    custodian: Optional[str] = None
    status: Optional[str] = None
    reason: Optional[str] = None


class SettlementsResponse(BaseModel):
    relationship_id: str
    pending: List[SettlementTrade] = []
    failed: List[SettlementTrade] = []
    as_of_date: str
