"""
Auth blueprint — no authentication required.

Scanner coverage (PythonScanner — Flask style):
  @bp.route("/login",   methods=["POST"]) → POST /api/auth/login
  @bp.route("/refresh", methods=["POST"]) → POST /api/auth/refresh
  @bp.route("/logout",  methods=["POST"]) → POST /api/auth/logout
"""
from flask import Blueprint, jsonify, request
from flask_jwt_extended import create_access_token, create_refresh_token, jwt_required, get_jwt_identity

bp = Blueprint("auth", __name__, url_prefix="/api/auth")

USERS = {
    "admin":    {"password": "admin123",    "role": "admin"},
    "user":     {"password": "user123",     "role": "user"},
    "readonly": {"password": "readonly123", "role": "readonly"},
}


@bp.route("/login", methods=["POST"])
def login():
    """Login with username + password. Returns JWT access and refresh token."""
    data = request.get_json(silent=True) or {}
    username = data.get("username", "")
    password = data.get("password", "")
    user = USERS.get(username)
    if not user or user["password"] != password:
        return jsonify({"message": "Invalid credentials"}), 401
    return jsonify({
        "access_token":  create_access_token(identity=username, additional_claims={"role": user["role"]}),
        "refresh_token": create_refresh_token(identity=username),
        "token_type":    "bearer",
        "expires_in":    3600,
    }), 200


@bp.route("/refresh", methods=["POST"])
@jwt_required(refresh=True)
def refresh():
    """Exchange a refresh token for a new access token."""
    username = get_jwt_identity()
    user = USERS.get(username, {"role": "user"})
    return jsonify({
        "access_token":  create_access_token(identity=username, additional_claims={"role": user["role"]}),
        "refresh_token": create_refresh_token(identity=username),
        "token_type":    "bearer",
        "expires_in":    3600,
    }), 200


@bp.route("/logout", methods=["POST"])
def logout():
    """Invalidate current session. Returns 204 No Content."""
    # In a real app: add token to denylist
    return "", 204
