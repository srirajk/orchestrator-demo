"""Pydantic response schemas for the Holdings agent."""
from pydantic import BaseModel, Field
from typing import List, Optional


class Position(BaseModel):
    ticker: str
    name: Optional[str] = None
    qty: Optional[float] = None
    value: float = Field(description="Market value in USD")
    asset_class: Optional[str] = None


class HoldingsResponse(BaseModel):
    relationship_id: str
    relationship_name: str
    positions: List[Position]
    total_market_value_usd: float
    as_of_date: str
    asset_allocation: Optional[dict] = None
