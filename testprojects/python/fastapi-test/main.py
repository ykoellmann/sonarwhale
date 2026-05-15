"""
Sonarwhale Test API — FastAPI

OpenAPI spec: http://localhost:8000/openapi.json  (Sonarwhale auto-discovery path)
Docs UI:      http://localhost:8000/docs

Run:  uvicorn main:app --reload --port 8000

Scanner coverage (PythonScanner — direct app endpoints, no router):
  @app.get("/health")      → GET /health       (no auth, tests inherit.off)
  @app.get("/api/version") → GET /api/version  (no auth)
  @app.options("/api/users") → OPTIONS /api/users  (CORS preflight)
"""
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse

from routers import admin, auth, orders, products, users

app = FastAPI(
    title="Sonarwhale Test API (Python / FastAPI)",
    version="1.0.0",
    description=(
        "Comprehensive test API covering all Sonarwhale features: "
        "all HTTP methods, all parameter locations (path/query/header/cookie), "
        "all auth types (Bearer JWT / API Key / Basic / OAuth2), "
        "complex request bodies, and scanner edge cases for PythonScanner."
    ),
    openapi_tags=[
        {"name": "Auth",     "description": "Authentication — no auth required"},
        {"name": "Users",    "description": "User management — Bearer JWT"},
        {"name": "Products", "description": "Product catalog — API Key (X-Api-Key header)"},
        {"name": "Orders",   "description": "Order management — Bearer JWT (OAuth2 scope)"},
        {"name": "Admin",    "description": "Admin operations — Basic auth"},
        {"name": "Public",   "description": "Public endpoints — no auth required"},
    ],
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)

# ── Routers ───────────────────────────────────────────────────────────────────
app.include_router(auth.router)
app.include_router(users.router)
app.include_router(products.router)
app.include_router(orders.router)
app.include_router(admin.router)

# ── Direct app endpoints (no router — tests @app.get style in PythonScanner) ─

@app.get("/health", tags=["Public"], summary="Health check — no auth required")
def health():
    """
    Health check endpoint.
    Tests the Sonarwhale inherit.off pattern: this endpoint should NOT inherit
    the global Bearer auth pre-script.
    """
    from datetime import datetime, timezone
    return {"status": "healthy", "version": "1.0.0", "timestamp": datetime.now(timezone.utc)}


@app.get("/api/version", tags=["Public"], summary="API version information")
def version():
    """Returns API version, commit hash, and runtime info."""
    import sys
    return {
        "version": "1.0.0",
        "commit":  "abc1234",
        "built_at": "2026-01-01T00:00:00Z",
        "python_version": sys.version,
    }


@app.options("/api/users", tags=["Public"], summary="CORS preflight — tests OPTIONS method")
def options_users():
    """
    CORS preflight response for /api/users.
    Tests that Sonarwhale correctly handles the OPTIONS HTTP method.
    """
    return JSONResponse(
        content={},
        headers={
            "Allow": "GET, POST, OPTIONS, HEAD",
            "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
            "Access-Control-Allow-Headers": "Authorization, Content-Type, X-Api-Key",
        },
    )
