"""
Shared test fixtures for the async Postgres-backed user-mgmt service.

Architecture:
- SQLite in-memory (StaticPool) as the DB backend — fast, no Docker needed
- fakeredis for auth codes (Redis SETEX operations)
- AsyncClient (httpx) for async HTTP calls
- BCRYPT_ROUNDS=4 to keep hashing fast
- Each test gets a fresh DB (tables dropped + recreated between tests)

Usage:
    pytest user-mgmt/tests/ -v
"""

import os

# Must be set before any app imports so _BCRYPT_ROUNDS picks it up at module load
os.environ.setdefault("BCRYPT_ROUNDS", "4")
# Point at SQLite so db.py engine is overridden before the app loads
os.environ.setdefault("DATABASE_URL", "sqlite+aiosqlite:///:memory:")

import asyncio
import json
from typing import AsyncGenerator

import fakeredis
import pytest
import pytest_asyncio
from httpx import ASGITransport, AsyncClient
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine
from sqlalchemy.pool import StaticPool

# Import db module so we can monkeypatch before any test runs
import db as db_module
import main as main_module
from db import get_db
from main import app
from models import Base


# ── SQLite test engine ────────────────────────────────────────────────────────

TEST_DATABASE_URL = "sqlite+aiosqlite:///:memory:"

test_engine = create_async_engine(
    TEST_DATABASE_URL,
    connect_args={"check_same_thread": False},
    poolclass=StaticPool,
    echo=False,
)

TestSessionLocal = async_sessionmaker(test_engine, expire_on_commit=False)


# ── Patch the engine before any test uses it ─────────────────────────────────

db_module.engine = test_engine
db_module.AsyncSessionLocal = TestSessionLocal

# Also patch main's direct import of AsyncSessionLocal (from db import AsyncSessionLocal)
# since Python binds the name at import time and patching db_module alone won't affect it.
main_module.AsyncSessionLocal = TestSessionLocal


# ── Dependency override: use test DB session ──────────────────────────────────

async def _override_get_db() -> AsyncGenerator[AsyncSession, None]:
    async with TestSessionLocal() as session:
        yield session


app.dependency_overrides[get_db] = _override_get_db


# ── fakeredis setup ───────────────────────────────────────────────────────────

_fake_redis = fakeredis.FakeRedis(decode_responses=True)


def _fake_get_redis():
    return _fake_redis


main_module.get_redis = _fake_get_redis
main_module._redis = _fake_redis


# ── Per-test DB reset fixture ─────────────────────────────────────────────────

@pytest_asyncio.fixture(autouse=True)
async def reset_db():
    """Drop all tables, recreate them, run the startup seed, and flush Redis."""
    # Reset RSA key + Redis on each test
    _fake_redis.flushall()

    # Recreate schema
    async with test_engine.begin() as conn:
        await conn.run_sync(Base.metadata.drop_all)
        await conn.run_sync(Base.metadata.create_all)

    # Run startup seeding (calls init_db + _seed_database using the patched engine)
    main_module._load_or_generate_keypair()
    main_module._init_registered_redirect_uris()
    # init_db will use the patched engine
    db_module.engine = test_engine
    db_module.AsyncSessionLocal = TestSessionLocal
    await main_module.startup()

    yield

    _fake_redis.flushall()


# ── Async HTTP client fixture ─────────────────────────────────────────────────

@pytest_asyncio.fixture
async def app_client() -> AsyncGenerator[AsyncClient, None]:
    """Async httpx client backed by the FastAPI ASGI app (no real HTTP port)."""
    async with AsyncClient(
        transport=ASGITransport(app=app),
        base_url="http://test",
    ) as client:
        yield client
