"""
SQLAlchemy ORM models for the Meridian user-mgmt service.

Uses JSON type (not JSONB) for SQLite compatibility in tests.
The `metadata` column uses Python attribute name `metadata_` to avoid
conflict with SQLAlchemy's own Base.metadata attribute.
"""

import uuid
from datetime import datetime

from sqlalchemy import DateTime, ForeignKey, Integer, String
from sqlalchemy.orm import DeclarativeBase, mapped_column
from sqlalchemy.sql import func
from sqlalchemy.types import JSON


class Base(DeclarativeBase):
    pass


class Principal(Base):
    """A user / service account that can be authenticated and authorized."""

    __tablename__ = "principals"

    id = mapped_column(String, primary_key=True)
    tenant_id = mapped_column(String, default="default")
    name = mapped_column(String, nullable=False)
    email = mapped_column(String, unique=True, nullable=False)
    password_hash = mapped_column(String)
    # attributes stores: clearance (int), segments (list), team (str), admin_domains (list)
    attributes = mapped_column(JSON, default=dict)
    status = mapped_column(String, default="active")
    created_at = mapped_column(DateTime, server_default=func.now())
    updated_at = mapped_column(DateTime, onupdate=func.now())


class Role(Base):
    """A named permission bundle (e.g., relationship_manager, platform_admin)."""

    __tablename__ = "roles"

    id = mapped_column(String, primary_key=True)
    tenant_id = mapped_column(String, default="default")
    name = mapped_column(String, nullable=False)
    description = mapped_column(String, default="")
    permissions = mapped_column(JSON, default=list)
    min_clearance = mapped_column(Integer, default=0)
    created_at = mapped_column(DateTime, server_default=func.now())


class Group(Base):
    """A named group of principals — type='team' or type='domain'."""

    __tablename__ = "groups"

    id = mapped_column(String, primary_key=True)
    tenant_id = mapped_column(String, default="default")
    type = mapped_column(String, default="team")  # "team" or "domain"
    name = mapped_column(String, nullable=False)
    slug = mapped_column(String, nullable=False)
    description = mapped_column(String, default="")
    # metadata_ maps to column "metadata" — stores type-specific data:
    #   teams:   {default_roles, segments, allowed_domains}
    #   domains: {relationships, admins}
    metadata_ = mapped_column("metadata", JSON, default=dict)
    created_at = mapped_column(DateTime, server_default=func.now())


class PrincipalRole(Base):
    """Many-to-many: which roles a principal holds."""

    __tablename__ = "principal_roles"

    principal_id = mapped_column(
        String,
        ForeignKey("principals.id", ondelete="CASCADE"),
        primary_key=True,
    )
    role_id = mapped_column(
        String,
        ForeignKey("roles.id", ondelete="CASCADE"),
        primary_key=True,
    )
    granted_by = mapped_column(String)
    granted_at = mapped_column(DateTime, server_default=func.now())


class GroupMember(Base):
    """Many-to-many: which principals belong to which groups (teams and domains)."""

    __tablename__ = "group_members"

    group_id = mapped_column(
        String,
        ForeignKey("groups.id", ondelete="CASCADE"),
        primary_key=True,
    )
    principal_id = mapped_column(
        String,
        ForeignKey("principals.id", ondelete="CASCADE"),
        primary_key=True,
    )
    granted_at = mapped_column(DateTime, server_default=func.now())


class PersonalResource(Base):
    """A resource (e.g., a relationship) explicitly granted to a principal."""

    __tablename__ = "personal_resources"

    id = mapped_column(String, primary_key=True, default=lambda: str(uuid.uuid4()))
    principal_id = mapped_column(
        String,
        ForeignKey("principals.id", ondelete="CASCADE"),
    )
    resource_type = mapped_column(String, nullable=False)  # "relationship", "patient", "matter", …
    resource_id = mapped_column(String, nullable=False)
    metadata_ = mapped_column("metadata", JSON, default=dict)
    granted_at = mapped_column(DateTime, server_default=func.now())
    expires_at = mapped_column(DateTime, nullable=True)


class AuditLog(Base):
    """Immutable audit trail for admin operations."""

    __tablename__ = "audit_log"

    id = mapped_column(String, primary_key=True, default=lambda: str(uuid.uuid4()))
    principal_id = mapped_column(String)
    action = mapped_column(String)
    resource_type = mapped_column(String)
    resource_id = mapped_column(String)
    metadata_ = mapped_column("metadata", JSON, default=dict)
    timestamp = mapped_column(DateTime, server_default=func.now())
