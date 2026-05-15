"""
Admin router — Basic auth required.

Scanner coverage (PythonScanner):
  @router.get("/users")            → GET    /api/admin/users
  @router.post("/users/{user_id}/ban") → POST /api/admin/users/{user_id}/ban
  @router.delete("/users/{user_id}")   → DELETE /api/admin/users/{user_id}
  @router.get("/stats")            → GET    /api/admin/stats
"""
from datetime import datetime, timezone
from typing import List, Optional

from fastapi import APIRouter, Depends, HTTPException, Query

from models.models import AdminUser, BanUserRequest, SystemStats, UserRole
from security import require_basic_auth

router = APIRouter(prefix="/api/admin", tags=["Admin"])

_STORE: List[AdminUser] = [
    AdminUser(id=1, username="admin",    email="admin@example.com",    role=UserRole.admin,    is_active=True,  is_banned=False, created_at=datetime(2026, 1, 1, tzinfo=timezone.utc), last_login_at=datetime(2026, 5, 14, tzinfo=timezone.utc)),
    AdminUser(id=2, username="alice",    email="alice@example.com",    role=UserRole.user,     is_active=True,  is_banned=False, created_at=datetime(2026, 2, 1, tzinfo=timezone.utc), last_login_at=datetime(2026, 5, 13, tzinfo=timezone.utc)),
    AdminUser(id=3, username="bob",      email="bob@example.com",      role=UserRole.user,     is_active=True,  is_banned=False, created_at=datetime(2026, 3, 1, tzinfo=timezone.utc), last_login_at=None),
    AdminUser(id=4, username="badactor", email="bad@example.com",      role=UserRole.user,     is_active=False, is_banned=True,  created_at=datetime(2025, 6, 1, tzinfo=timezone.utc), last_login_at=None),
]


@router.get("/users", response_model=List[AdminUser])
def get_users(
    include_banned: bool = Query(default=True, description="Include banned users in results"),
    _auth: bool = Depends(require_basic_auth),
):
    """Admin view of all users including banned accounts. Requires Basic auth."""
    return _STORE if include_banned else [u for u in _STORE if not u.is_banned]


@router.post("/users/{user_id}/ban", status_code=200)
def ban_user(
    user_id: int,
    request: BanUserRequest,
    _auth: bool = Depends(require_basic_auth),
):
    """Ban a user account."""
    idx = next((i for i, u in enumerate(_STORE) if u.id == user_id), None)
    if idx is None:
        raise HTTPException(status_code=404, detail=f"User {user_id} not found")
    _STORE[idx] = _STORE[idx].model_copy(update={"is_banned": True, "is_active": False})
    return {"message": f"User {user_id} banned", "reason": request.reason, "ban_until": request.ban_until}


@router.delete("/users/{user_id}", status_code=204)
def delete_user(user_id: int, _auth: bool = Depends(require_basic_auth)):
    """Permanently delete a user (hard delete)."""
    user = next((u for u in _STORE if u.id == user_id), None)
    if not user:
        raise HTTPException(status_code=404, detail=f"User {user_id} not found")
    _STORE.remove(user)
    return None


@router.get("/stats", response_model=SystemStats)
def get_stats(_auth: bool = Depends(require_basic_auth)):
    """System statistics and usage metrics."""
    return SystemStats(
        total_users=len(_STORE),
        active_users=sum(1 for u in _STORE if u.is_active),
        total_products=4,
        total_orders=2,
        total_revenue=1459.96,
        generated_at=datetime.now(timezone.utc),
    )
