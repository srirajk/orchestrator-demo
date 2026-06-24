from pydantic import BaseModel
from typing import Optional


class NAVResponse(BaseModel):
    fund_id: str
    fund_name: str
    nav: float
    as_of_date: str
    currency: str
    aum: Optional[float] = None
    shares_outstanding: Optional[float] = None
    nav_change_pct: Optional[float] = None
