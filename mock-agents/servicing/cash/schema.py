from pydantic import BaseModel
from typing import List, Optional


class CashBalance(BaseModel):
    currency: str
    settled: float
    unsettled: float
    total: float


class CashManagementResponse(BaseModel):
    relationship_id: str
    balances: List[CashBalance]
    projected_cash_usd: float
    as_of_date: str
    note: Optional[str] = None
