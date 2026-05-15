"""JWT + API Key security utilities for the FastAPI test project."""
from datetime import datetime, timedelta, timezone
from typing import Optional

from fastapi import Depends, Header, HTTPException, Security, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer, OAuth2PasswordBearer
from jose import JWTError, jwt

SECRET_KEY = "sonarwhale-test-secret-key-32chars!"
ALGORITHM  = "HS256"
VALID_API_KEY = "test-api-key-12345"

# In-memory "users" for demo purposes
USERS = {
    "admin":    {"password": "admin123",    "role": "admin"},
    "user":     {"password": "user123",     "role": "user"},
    "readonly": {"password": "readonly123", "role": "readonly"},
}

bearer_scheme = HTTPBearer(auto_error=False)
oauth2_scheme = OAuth2PasswordBearer(tokenUrl="/api/auth/login", auto_error=False)


def create_access_token(username: str, role: str) -> str:
    payload = {
        "sub":  username,
        "role": role,
        "exp":  datetime.now(timezone.utc) + timedelta(hours=1),
        "iat":  datetime.now(timezone.utc),
    }
    return jwt.encode(payload, SECRET_KEY, algorithm=ALGORITHM)


def create_refresh_token(username: str) -> str:
    return f"refresh_{username}_{datetime.now(timezone.utc).timestamp()}"


def decode_token(token: str) -> dict:
    try:
        return jwt.decode(token, SECRET_KEY, algorithms=[ALGORITHM])
    except JWTError as e:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail=f"Invalid or expired token: {e}",
            headers={"WWW-Authenticate": "Bearer"},
        )


def get_current_user(
    credentials: Optional[HTTPAuthorizationCredentials] = Security(bearer_scheme),
) -> dict:
    if not credentials:
        raise HTTPException(status_code=401, detail="Bearer token required")
    return decode_token(credentials.credentials)


def require_api_key(x_api_key: Optional[str] = Header(default=None)) -> str:
    if x_api_key != VALID_API_KEY:
        raise HTTPException(status_code=401, detail="Invalid or missing X-Api-Key header")
    return x_api_key


def require_basic_auth(authorization: Optional[str] = Header(default=None)) -> bool:
    import base64
    if not authorization or not authorization.startswith("Basic "):
        raise HTTPException(status_code=401, detail="Basic auth required")
    try:
        decoded = base64.b64decode(authorization[6:]).decode()
        if decoded != "admin:admin123":
            raise HTTPException(status_code=403, detail="Forbidden")
        return True
    except Exception:
        raise HTTPException(status_code=401, detail="Invalid Basic auth")
