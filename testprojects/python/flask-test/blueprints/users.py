"""
Users blueprint — Bearer JWT auth required.

Scanner coverage (PythonScanner — Flask-specific patterns):
  @bp.route("/",           methods=["GET"])                     → GET    /api/users
  @bp.route("/",           methods=["POST"])                    → POST   /api/users
  @bp.route("/<int:user_id>", methods=["GET"])                  → GET    /api/users/{user_id}
  @bp.route("/<int:user_id>", methods=["PUT"])                  → PUT    /api/users/{user_id}
  @bp.route("/<int:user_id>", methods=["PATCH"])                → PATCH  /api/users/{user_id}
  @bp.route("/<int:user_id>", methods=["DELETE"])               → DELETE /api/users/{user_id}
  @bp.route("/<int:user_id>/avatar", methods=["GET"])           → GET    /api/users/{user_id}/avatar
  @bp.route("/<int:user_id>/avatar", methods=["POST"])          → POST   /api/users/{user_id}/avatar

  FLASK SCANNER EDGE CASES:
  - <int:user_id>  type-prefixed param  → normalised to {user_id}
  - multiple methods on same route decorator (GET+HEAD)
  - @bp.route with explicit methods=[] list
"""
from datetime import datetime, timezone

from flask import Blueprint, jsonify, request, send_file
from flask_jwt_extended import jwt_required
import io

bp = Blueprint("users", __name__, url_prefix="/api/users")

_STORE = [
    {"id": 1, "username": "admin",    "email": "admin@example.com",    "role": "admin",    "is_active": True,  "created_at": "2026-01-01T00:00:00Z"},
    {"id": 2, "username": "alice",    "email": "alice@example.com",    "role": "user",     "is_active": True,  "created_at": "2026-02-01T00:00:00Z"},
    {"id": 3, "username": "bob",      "email": "bob@example.com",      "role": "user",     "is_active": True,  "created_at": "2026-03-01T00:00:00Z"},
    {"id": 4, "username": "inactive", "email": "inactive@example.com", "role": "user",     "is_active": False, "created_at": "2025-06-01T00:00:00Z"},
]


# ── List users ────────────────────────────────────────────────────────────────

@bp.route("/", methods=["GET"])
@jwt_required()
def list_users():
    """List all users (paginated). Bearer JWT required."""
    page      = int(request.args.get("page", 1))
    page_size = int(request.args.get("page_size", 20))
    search    = request.args.get("search", "")
    q = _STORE if not search else [
        u for u in _STORE if search.lower() in u["username"].lower() or search.lower() in u["email"].lower()
    ]
    return jsonify({"items": q[(page-1)*page_size : page*page_size],
                    "page": page, "page_size": page_size, "total_count": len(q)}), 200


# ── Create user ───────────────────────────────────────────────────────────────

@bp.route("/", methods=["POST"])
@jwt_required()
def create_user():
    """Create a new user. Returns 201 with created resource."""
    data = request.get_json(silent=True) or {}
    if any(u["username"] == data.get("username") for u in _STORE):
        return jsonify({"message": f"Username '{data.get('username')}' already taken"}), 409
    user = {
        "id":         max(u["id"] for u in _STORE) + 1,
        "username":   data.get("username", ""),
        "email":      data.get("email", ""),
        "role":       data.get("role", "user"),
        "is_active":  True,
        "created_at": datetime.now(timezone.utc).isoformat(),
    }
    _STORE.append(user)
    return jsonify(user), 201


# ── Single-user operations with Flask <type:param> syntax ────────────────────
# Note: GET + HEAD on the same route (multi-method, Flask scanner edge case)

@bp.route("/<int:user_id>", methods=["GET", "HEAD"])
@jwt_required()
def get_user(user_id):
    """Get a single user by ID. Also handles HEAD (no body)."""
    user = next((u for u in _STORE if u["id"] == user_id), None)
    if not user:
        return jsonify({"message": f"User {user_id} not found"}), 404
    if request.method == "HEAD":
        return "", 200
    return jsonify(user), 200


@bp.route("/<int:user_id>", methods=["PUT"])
@jwt_required()
def update_user(user_id):
    """Full update of a user."""
    idx = next((i for i, u in enumerate(_STORE) if u["id"] == user_id), None)
    if idx is None:
        return jsonify({"message": f"User {user_id} not found"}), 404
    data = request.get_json(silent=True) or {}
    for key in ("username", "email", "role"):
        if key in data:
            _STORE[idx][key] = data[key]
    return jsonify(_STORE[idx]), 200


@bp.route("/<int:user_id>", methods=["PATCH"])
@jwt_required()
def partial_update_user(user_id):
    """Partial update — only provided fields are changed."""
    return update_user(user_id)


@bp.route("/<int:user_id>", methods=["DELETE"])
@jwt_required()
def delete_user(user_id):
    """Delete a user permanently."""
    user = next((u for u in _STORE if u["id"] == user_id), None)
    if not user:
        return jsonify({"message": f"User {user_id} not found"}), 404
    _STORE.remove(user)
    return "", 204


# ── Avatar ────────────────────────────────────────────────────────────────────

@bp.route("/<int:user_id>/avatar", methods=["GET"])
@jwt_required()
def get_avatar(user_id):
    """Download user avatar. Accept header can request image/png or image/jpeg."""
    if not any(u["id"] == user_id for u in _STORE):
        return jsonify({"message": "Not found"}), 404
    # 1×1 transparent PNG
    png = bytes.fromhex(
        "89504e470d0a1a0a0000000d494844520000000100000001080600000"
        "01f15c489000000110049444154789c6260f8cfc00000000200017e21"
        "bc330000000049454e44ae426082"
    )
    return send_file(io.BytesIO(png), mimetype="image/png",
                     download_name=f"avatar_{user_id}.png")


@bp.route("/<int:user_id>/avatar", methods=["POST"])
@jwt_required()
def upload_avatar(user_id):
    """Upload a new avatar (multipart/form-data)."""
    if not any(u["id"] == user_id for u in _STORE):
        return jsonify({"message": "Not found"}), 404
    file = request.files.get("file")
    if not file:
        return jsonify({"message": "No file provided"}), 400
    if not file.content_type.startswith("image/"):
        return jsonify({"message": "File must be an image"}), 400
    data = file.read()
    return jsonify({"message": "Avatar uploaded", "size": len(data), "content_type": file.content_type}), 200
