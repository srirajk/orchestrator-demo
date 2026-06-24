from pydantic import BaseModel
from typing import List, Optional


class CustodyHolding(BaseModel):
    isin: str
    security: str
    qty: float
    value: float


class CustodianAccount(BaseModel):
    custodian: str
    account: str
    holdings: List[CustodyHolding]


class CustodyPositionsResponse(BaseModel):
    relationship_id: str
    holdings_by_custodian: List[CustodianAccount]
    as_of_date: str
