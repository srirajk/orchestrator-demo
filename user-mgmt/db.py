"""
Async SQLAlchemy engine + session factory for the Meridian user-mgmt service.

DATABASE_URL defaults to the PostgreSQL URL used inside Docker Compose.
Override via env var for testing (sqlite+aiosqlite:///:memory:).
"""

import os

from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine

from models import Base

DATABASE_URL = os.environ.get(
    "DATABASE_URL",
    "postgresql+asyncpg://meridian:meridian_dev@postgres:5432/meridian",
)

engine = create_async_engine(DATABASE_URL, echo=False, pool_pre_ping=True)
AsyncSessionLocal = async_sessionmaker(engine, expire_on_commit=False)


async def get_db():
    """FastAPI dependency: yields an AsyncSession per request."""
    async with AsyncSessionLocal() as session:
        yield session


async def init_db() -> None:
    """Create all tables if they do not already exist (idempotent)."""
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)
