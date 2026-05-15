"""
Auth router — no authentication required on any endpoint.

Scanner coverage (PythonScanner):
  @router.post("/login")    → POST /api/auth/login
  @router.post("/refresh")  → POST /api/auth/refresh
  @router.post("/logout")   → POST /api/auth/logout
"""
from fastapi import APIRouter, HTTPException, status
from models.models import LoginRequest, RefreshRequest, TokenResponse
from security import USERS, create_access_token, create_refresh_token

router = APIRouter(prefix="/api/auth", tags=["Auth"])


@router.post("/login", response_model=TokenResponse, status_code=200)
def login(request: LoginRequest):
    """Login with username and password. Returns JWT access + refresh token."""
    user = USERS.get(request.username)
    if not user or user["password"] != request.password:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid credentials",
            headers={"WWW-Authenticate": "Bearer"},
        )
    return TokenResponse(
        access_token=create_access_token(request.username, user["role"]),
        refresh_token=create_refresh_token(request.username),
    )


@router.post("/refresh", response_model=TokenResponse)
def refresh(request: RefreshRequest):
    """Exchange a refresh token for a new access token."""
    if not request.refresh_token.startswith("refresh_"):
        raise HTTPException(status_code=401, detail="Invalid refresh token")
    parts = request.refresh_token.split("_")
    username = parts[1] if len(parts) > 1 else "user"
    user = USERS.get(username, {"role": "user"})
    return TokenResponse(
        access_token=create_access_token(username, user["role"]),
        refresh_token=create_refresh_token(username),
    )


@router.post("/logout", status_code=204)
def logout():
    """Invalidate the current session. Returns 204 No Content."""
    # In a real app: blacklist the token / remove refresh token from DB.
    return None
