"""
Sonarwhale Test API — Flask

OpenAPI spec: http://localhost:5001/openapi/v3.0.json
Docs UI:      http://localhost:5001/docs

Run:  python app.py   (or: flask --app app run --port 5001 --debug)

Scanner coverage (PythonScanner — direct @app.route and blueprint styles):
  @app.route("/health")      methods=["GET"]  → GET /health
  @app.route("/api/version") methods=["GET"]  → GET /api/version
  @app.route("/api/users")   methods=["OPTIONS"] → OPTIONS /api/users
"""
import json
from datetime import datetime, timezone

from flask import Flask, jsonify, request
from flask_jwt_extended import JWTManager

from blueprints.auth import bp as auth_bp
from blueprints.products import bp as products_bp
from blueprints.users import bp as users_bp

app = Flask(__name__)
app.config["JWT_SECRET_KEY"] = "sonarwhale-test-secret-key-flask"
app.config["JWT_ACCESS_TOKEN_EXPIRES"] = 3600

jwt = JWTManager(app)

# ── Blueprints ────────────────────────────────────────────────────────────────
app.register_blueprint(auth_bp)
app.register_blueprint(users_bp)
app.register_blueprint(products_bp)

# ── Direct @app.route endpoints (tests @app.route pattern in PythonScanner) ──

@app.route("/health", methods=["GET"])
def health():
    """
    Health check — no auth.
    Tests PythonScanner @app.route detection (not @bp.route).
    Also tests the Sonarwhale inherit.off pattern.
    """
    return jsonify({"status": "healthy", "version": "1.0.0",
                    "timestamp": datetime.now(timezone.utc).isoformat()}), 200


@app.route("/api/version", methods=["GET"])
def version():
    """API version info — no auth."""
    import sys
    return jsonify({"version": "1.0.0", "commit": "abc1234",
                    "built_at": "2026-01-01T00:00:00Z",
                    "python": sys.version}), 200


@app.route("/api/users", methods=["OPTIONS"])
def options_users():
    """
    CORS preflight — OPTIONS method.
    Tests PythonScanner detection of OPTIONS verb on @app.route.
    """
    response = jsonify({})
    response.headers["Allow"] = "GET, POST, OPTIONS, HEAD"
    response.headers["Access-Control-Allow-Methods"] = "GET, POST, OPTIONS"
    response.headers["Access-Control-Allow-Headers"] = "Authorization, Content-Type, X-Api-Key"
    return response, 200


# ── Manual OpenAPI spec (Flask doesn't auto-generate — provide static spec) ──
# Sonarwhale can be pointed at /openapi.json for auto-discovery

@app.route("/openapi.json", methods=["GET"])
def openapi_spec():
    """
    Serve a static OpenAPI spec for Sonarwhale auto-discovery.
    In a real Flask project you'd use flask-openapi3 or flasgger for this.
    This minimal spec lists all routes so Sonarwhale can discover them.
    """
    spec = {
        "openapi": "3.0.3",
        "info": {
            "title": "Sonarwhale Test API (Python / Flask)",
            "version": "1.0.0",
            "description": "Flask test API for Sonarwhale scanner testing. Covers @app.route and Blueprint routing styles, Flask <type:param> path parameters, and multi-method route decorators."
        },
        "components": {
            "securitySchemes": {
                "Bearer": {"type": "http", "scheme": "bearer", "bearerFormat": "JWT"},
                "ApiKey": {"type": "apiKey", "in": "header", "name": "X-Api-Key"},
                "BasicAuth": {"type": "http", "scheme": "basic"}
            }
        },
        "paths": {
            "/api/auth/login":   {"post":   {"tags": ["Auth"],     "summary": "Login",           "security": [], "requestBody": {"required": True, "content": {"application/json": {"schema": {"type": "object", "properties": {"username": {"type": "string"}, "password": {"type": "string"}}}}}}, "responses": {"200": {"description": "Token response"}, "401": {"description": "Invalid credentials"}}}},
            "/api/auth/refresh": {"post":   {"tags": ["Auth"],     "summary": "Refresh token",   "security": [{"Bearer": []}], "responses": {"200": {"description": "New tokens"}}}},
            "/api/auth/logout":  {"post":   {"tags": ["Auth"],     "summary": "Logout",          "security": [], "responses": {"204": {"description": "Logged out"}}}},
            "/api/users": {
                "get":     {"tags": ["Users"],    "summary": "List users",     "security": [{"Bearer": []}], "parameters": [{"name": "page", "in": "query", "schema": {"type": "integer"}}, {"name": "page_size", "in": "query", "schema": {"type": "integer"}}, {"name": "search", "in": "query", "schema": {"type": "string"}}], "responses": {"200": {"description": "User list"}}},
                "post":    {"tags": ["Users"],    "summary": "Create user",    "security": [{"Bearer": []}], "requestBody": {"required": True, "content": {"application/json": {"schema": {"type": "object", "properties": {"username": {"type": "string"}, "email": {"type": "string"}, "password": {"type": "string"}, "role": {"type": "string", "enum": ["admin", "user", "readonly"]}}}}}}, "responses": {"201": {"description": "Created"}, "409": {"description": "Conflict"}}},
                "options": {"tags": ["Public"],   "summary": "CORS preflight", "security": [], "responses": {"200": {"description": "CORS headers"}}},
            },
            "/api/users/{user_id}": {
                "get":    {"tags": ["Users"], "summary": "Get user",    "security": [{"Bearer": []}], "parameters": [{"name": "user_id", "in": "path", "required": True, "schema": {"type": "integer"}}], "responses": {"200": {"description": "User"}, "404": {"description": "Not found"}}},
                "head":   {"tags": ["Users"], "summary": "User exists", "security": [{"Bearer": []}], "parameters": [{"name": "user_id", "in": "path", "required": True, "schema": {"type": "integer"}}], "responses": {"200": {"description": "Exists"}, "404": {"description": "Not found"}}},
                "put":    {"tags": ["Users"], "summary": "Update user", "security": [{"Bearer": []}], "parameters": [{"name": "user_id", "in": "path", "required": True, "schema": {"type": "integer"}}], "requestBody": {"required": True, "content": {"application/json": {"schema": {"type": "object"}}}}, "responses": {"200": {"description": "Updated"}}},
                "patch":  {"tags": ["Users"], "summary": "Patch user",  "security": [{"Bearer": []}], "parameters": [{"name": "user_id", "in": "path", "required": True, "schema": {"type": "integer"}}], "requestBody": {"required": True, "content": {"application/json": {"schema": {"type": "object"}}}}, "responses": {"200": {"description": "Updated"}}},
                "delete": {"tags": ["Users"], "summary": "Delete user", "security": [{"Bearer": []}], "parameters": [{"name": "user_id", "in": "path", "required": True, "schema": {"type": "integer"}}], "responses": {"204": {"description": "Deleted"}}},
            },
            "/api/users/{user_id}/avatar": {
                "get":  {"tags": ["Users"], "summary": "Get avatar",    "security": [{"Bearer": []}], "parameters": [{"name": "user_id", "in": "path", "required": True, "schema": {"type": "integer"}}, {"name": "Accept", "in": "header", "schema": {"type": "string"}}], "responses": {"200": {"description": "Image file"}}},
                "post": {"tags": ["Users"], "summary": "Upload avatar", "security": [{"Bearer": []}], "parameters": [{"name": "user_id", "in": "path", "required": True, "schema": {"type": "integer"}}], "requestBody": {"required": True, "content": {"multipart/form-data": {"schema": {"type": "object", "properties": {"file": {"type": "string", "format": "binary"}}}}}}, "responses": {"200": {"description": "Uploaded"}}},
            },
            "/api/products":                         {"get": {"tags": ["Products"], "summary": "List products",   "security": [{"ApiKey": []}], "parameters": [{"name": "category", "in": "query", "schema": {"type": "string"}}, {"name": "in_stock", "in": "query", "schema": {"type": "boolean"}}, {"name": "min_price", "in": "query", "schema": {"type": "number"}}, {"name": "max_price", "in": "query", "schema": {"type": "number"}}], "responses": {"200": {"description": "Product list"}}}, "post": {"tags": ["Products"], "summary": "Create product", "security": [{"ApiKey": []}], "requestBody": {"required": True, "content": {"application/json": {"schema": {"type": "object"}}}}, "responses": {"201": {"description": "Created"}}}},
            "/api/products/search":                  {"get": {"tags": ["Products"], "summary": "Search products", "security": [{"ApiKey": []}], "parameters": [{"name": "q", "in": "query", "required": True, "schema": {"type": "string"}}, {"name": "in_stock", "in": "query", "schema": {"type": "boolean"}}], "responses": {"200": {"description": "Results"}}}},
            "/api/products/{product_id}":            {"get": {"tags": ["Products"], "summary": "Get product",    "security": [{"ApiKey": []}], "parameters": [{"name": "product_id", "in": "path", "required": True, "schema": {"type": "integer"}}], "responses": {"200": {"description": "Product"}}}, "put": {"tags": ["Products"], "summary": "Update product", "security": [{"ApiKey": []}], "parameters": [{"name": "product_id", "in": "path", "required": True, "schema": {"type": "integer"}}], "requestBody": {"required": True, "content": {"application/json": {"schema": {"type": "object"}}}}, "responses": {"200": {"description": "Updated"}}}, "delete": {"tags": ["Products"], "summary": "Delete product", "security": [{"ApiKey": []}], "parameters": [{"name": "product_id", "in": "path", "required": True, "schema": {"type": "integer"}}], "responses": {"204": {"description": "Deleted"}}}},
            "/api/products/{product_id}/variants":   {"get": {"tags": ["Products"], "summary": "List variants",  "security": [{"ApiKey": []}], "parameters": [{"name": "product_id", "in": "path", "required": True, "schema": {"type": "integer"}}], "responses": {"200": {"description": "Variants"}}}},
            "/api/products/by-name/{name}":          {"get": {"tags": ["Products"], "summary": "Get by name",   "security": [{"ApiKey": []}], "parameters": [{"name": "name", "in": "path", "required": True, "schema": {"type": "string"}}], "responses": {"200": {"description": "Product"}}}},
            "/health":       {"get": {"tags": ["Public"], "summary": "Health check",   "security": [], "responses": {"200": {"description": "Healthy"}}}},
            "/api/version":  {"get": {"tags": ["Public"], "summary": "Version info",   "security": [], "responses": {"200": {"description": "Version"}}}},
        }
    }
    return jsonify(spec), 200


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=5001, debug=True)
