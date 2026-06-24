from pydantic import BaseModel
from typing import List, Optional


class Goal(BaseModel):
    goal_id: str
    name: str
    target_amount: float
    current_amount: float
    target_date: str
    on_track: bool
    progress_pct: float
    shortfall: Optional[float] = None


class GoalPlanningResponse(BaseModel):
    relationship_id: str
    overall_on_track: bool
    goals: List[Goal]
    summary: Optional[str] = None
