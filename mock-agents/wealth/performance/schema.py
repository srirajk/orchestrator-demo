from pydantic import BaseModel
from typing import Optional


class PerformanceResponse(BaseModel):
    relationship_id: str
    period: str
    total_return_pct: float
    pnl: float
    benchmark_return_pct: Optional[float] = None
    alpha: float
    volatility_pct: Optional[float] = None
    sharpe_ratio: Optional[float] = None
    as_of_date: str
