"""
Users router — Bearer JWT auth required.

Scanner coverage (PythonScanner):
  @router.get("/")                → GET  /api/users          (router root)
  @router.get("/{user_id}")       → GET  /api/users/{user_id}
  @router.post("/")               → POST /api/users
  @router.put("/{user_id}")       → PUT  /api/users/{user_id}
  @router.patch("/{user_id}")     → PATCH /api/users/{user_id}
  @router.delete("/{user_id}")    → DELETE /api/users/{user_id}
  @router.get("/{user_id}/avatar")  → GET  /api/users/{user_id}/avatar
  @router.post("/{user_id}/avatar") → POST /api/users/{user_id}/avatar (multipart)
"""
from datetime import datetime, timezone
from typing import List, Optional

from fastapi import APIRouter, Depends, File, Header, HTTPException, Query, UploadFile, status
from fastapi.responses import Response

from models.models import (
    CreateUserRequest, PagedResult, UpdateUserRequest, User, UserRole,
)
from security import get_current_user

router = APIRouter(prefix="/api/users", tags=["Users"])

# In-memory store
_STORE: List[User] = [
    User(id=1, username="admin",    email="admin@example.com",    role=UserRole.admin,    is_active=True,  created_at=datetime(2026, 1, 1, tzinfo=timezone.utc)),
    User(id=2, username="alice",    email="alice@example.com",    role=UserRole.user,     is_active=True,  created_at=datetime(2026, 2, 1, tzinfo=timezone.utc)),
    User(id=3, username="bob",      email="bob@example.com",      role=UserRole.user,     is_active=True,  created_at=datetime(2026, 3, 1, tzinfo=timezone.utc)),
    User(id=4, username="inactive", email="inactive@example.com", role=UserRole.user,     is_active=False, created_at=datetime(2025, 6, 1, tzinfo=timezone.utc)),
]


@router.get("/", response_model=PagedResult[User])
def list_users(
    page: int = Query(default=1, ge=1, description="Page number (1-based)"),
    page_size: int = Query(default=20, ge=1, le=100, description="Items per page"),
    search: Optional[str] = Query(default=None, description="Filter by username or email"),
    sort: str = Query(default="username", description="Sort field: username | email | created_at"),
    current_user: dict = Depends(get_current_user),
):
    """List all users (paginated). Requires Bearer JWT."""
    q = _STORE
    if search:
        q = [u for u in q if search.lower() in u.username.lower() or search.lower() in u.email.lower()]
    total = len(q)
    items = q[(page - 1) * page_size : page * page_size]
    return PagedResult(items=items, page=page, page_size=page_size, total_count=total)


@router.get("/{user_id}", response_model=User)
def get_user(user_id: int, current_user: dict = Depends(get_current_user)):
    """Get a single user by ID."""
    user = next((u for u in _STORE if u.id == user_id), None)
    if not user:
        raise HTTPException(status_code=404, detail=f"User {user_id} not found")
    return user


@router.post("/", response_model=User, status_code=status.HTTP_201_CREATED)
def create_user(
    request: CreateUserRequest,
    current_user: dict = Depends(get_current_user),
):
    """Create a new user. Returns 201 with the created user."""
    if any(u.username == request.username for u in _STORE):
        raise HTTPException(status_code=409, detail=f"Username '{request.username}' already taken")
    user = User(
        id=max(u.id for u in _STORE) + 1,
        username=request.username,
        email=request.email,
        role=request.role,
        is_active=True,
        created_at=datetime.now(timezone.utc),
    )
    _STORE.append(user)
    return user


@router.put("/{user_id}", response_model=User)
def update_user(
    user_id: int,
    request: UpdateUserRequest,
    current_user: dict = Depends(get_current_user),
):
    """Full update of a user (all optional fields merged)."""
    idx = next((i for i, u in enumerate(_STORE) if u.id == user_id), None)
    if idx is None:
        raise HTTPException(status_code=404, detail=f"User {user_id} not found")
    existing = _STORE[idx]
    _STORE[idx] = existing.model_copy(update={
        k: v for k, v in request.model_dump().items() if v is not None
    })
    return _STORE[idx]


@router.patch("/{user_id}", response_model=User)
def partial_update_user(
    user_id: int,
    request: UpdateUserRequest,
    current_user: dict = Depends(get_current_user),
):
    """Partial update — only provided (non-null) fields are changed."""
    return update_user(user_id, request, current_user)


@router.delete("/{user_id}", status_code=204)
def delete_user(user_id: int, current_user: dict = Depends(get_current_user)):
    """Delete a user permanently. Returns 204 No Content."""
    user = next((u for u in _STORE if u.id == user_id), None)
    if not user:
        raise HTTPException(status_code=404, detail=f"User {user_id} not found")
    _STORE.remove(user)
    return None


@router.get("/{user_id}/avatar")
def get_avatar(
    user_id: int,
    accept: Optional[str] = Header(default=None, alias="Accept"),
    current_user: dict = Depends(get_current_user),
):
    """Download the user's avatar image. Accept header selects format."""
    user = next((u for u in _STORE if u.id == user_id), None)
    if not user:
        raise HTTPException(status_code=404, detail=f"User {user_id} not found")
    # Return a 1×1 transparent PNG as placeholder
    png = bytes.fromhex(
        "89504e470d0a1a0a0000000d494844520000000100000001080600000"
        "01f15c489000000110049444154789c6260f8cfc00000000200017e21"
        "bc330000000049454e44ae426082"
    )
    return Response(content=png, media_type="image/png",
                    headers={"Content-Disposition": f'attachment; filename="avatar_{user_id}.png"'})


@router.post("/{user_id}/avatar", status_code=200)
async def upload_avatar(
    user_id: int,
    file: UploadFile = File(..., description="Avatar image (JPEG/PNG, max 5 MB)"),
    current_user: dict = Depends(get_current_user),
):
    """Upload a new avatar for a user (multipart/form-data)."""
    user = next((u for u in _STORE if u.id == user_id), None)
    if not user:
        raise HTTPException(status_code=404, detail=f"User {user_id} not found")
    if not file.content_type or not file.content_type.startswith("image/"):
        raise HTTPException(status_code=400, detail="File must be an image")
    content = await file.read()
    if len(content) > 5 * 1024 * 1024:
        raise HTTPException(status_code=400, detail="File too large (max 5 MB)")
    return {"message": "Avatar uploaded", "size": len(content), "content_type": file.content_type}
