"""
Products blueprint — API Key auth (X-Api-Key header).

Scanner coverage (PythonScanner — more Flask patterns):
  @bp.route("/",                           methods=["GET"])    → GET    /api/products
  @bp.route("/search",                     methods=["GET"])    → GET    /api/products/search
  @bp.route("/<int:product_id>",           methods=["GET"])    → GET    /api/products/{product_id}
  @bp.route("/<int:product_id>",           methods=["PUT"])    → PUT    /api/products/{product_id}
  @bp.route("/<int:product_id>",           methods=["DELETE"]) → DELETE /api/products/{product_id}
  @bp.route("/<int:product_id>/variants",  methods=["GET"])    → GET    /api/products/{product_id}/variants
  @bp.route("/",                           methods=["POST"])   → POST   /api/products

  Flask edge cases:
  - <int:product_id> → {product_id}
  - <string:name>    → {name}    (see /by-name route)
"""
from flask import Blueprint, jsonify, request

bp = Blueprint("products", __name__, url_prefix="/api/products")

VALID_API_KEY = "test-api-key-12345"

_STORE = [
    {"id": 1, "name": "Laptop Pro 15",       "description": "High-end developer laptop", "price": 1299.99, "category": "electronics", "in_stock": True,  "stock_count": 12},
    {"id": 2, "name": "Mechanical Keyboard",  "description": "Clicky switches",           "price": 89.99,   "category": "electronics", "in_stock": True,  "stock_count": 45},
    {"id": 3, "name": "Clean Code",           "description": "Book by Robert C. Martin",  "price": 34.99,   "category": "books",       "in_stock": True,  "stock_count": 200},
    {"id": 4, "name": "Coffee Beans",         "description": "Ethiopian single origin",    "price": 18.50,   "category": "food",        "in_stock": False, "stock_count": 0},
]

def _check_api_key():
    key = request.headers.get("X-Api-Key")
    if key != VALID_API_KEY:
        return jsonify({"message": "Invalid or missing X-Api-Key"}), 401
    return None


@bp.route("/", methods=["GET"])
def list_products():
    """List products with optional filters."""
    err = _check_api_key();
    if err: return err
    category  = request.args.get("category")
    in_stock  = request.args.get("in_stock")
    min_price = request.args.get("min_price", type=float)
    max_price = request.args.get("max_price", type=float)
    q = _STORE
    if category:   q = [p for p in q if p["category"] == category]
    if in_stock:   q = [p for p in q if p["in_stock"] == (in_stock.lower() == "true")]
    if min_price:  q = [p for p in q if p["price"] >= min_price]
    if max_price:  q = [p for p in q if p["price"] <= max_price]
    return jsonify(q), 200


@bp.route("/search", methods=["GET"])
def search_products():
    """Full-text product search."""
    err = _check_api_key();
    if err: return err
    q_str = request.args.get("q", "")
    results = [p for p in _STORE if q_str.lower() in p["name"].lower() or q_str.lower() in p["description"].lower()]
    return jsonify(results), 200


@bp.route("/", methods=["POST"])
def create_product():
    """Create a new product."""
    err = _check_api_key();
    if err: return err
    data = request.get_json(silent=True) or {}
    product = {
        "id":          max(p["id"] for p in _STORE) + 1,
        "name":        data.get("name", ""),
        "description": data.get("description", ""),
        "price":       data.get("price", 0.0),
        "category":    data.get("category", "other"),
        "in_stock":    data.get("stock_count", 0) > 0,
        "stock_count": data.get("stock_count", 0),
    }
    _STORE.append(product)
    return jsonify(product), 201


@bp.route("/<int:product_id>", methods=["GET"])
def get_product(product_id):
    """Get a product by ID. <int:product_id> → {product_id}."""
    err = _check_api_key();
    if err: return err
    p = next((p for p in _STORE if p["id"] == product_id), None)
    return (jsonify(p), 200) if p else (jsonify({"message": "Not found"}), 404)


@bp.route("/<int:product_id>", methods=["PUT"])
def update_product(product_id):
    """Full update of a product."""
    err = _check_api_key();
    if err: return err
    idx = next((i for i, p in enumerate(_STORE) if p["id"] == product_id), None)
    if idx is None:
        return jsonify({"message": "Not found"}), 404
    data = request.get_json(silent=True) or {}
    _STORE[idx].update({k: v for k, v in data.items() if v is not None})
    return jsonify(_STORE[idx]), 200


@bp.route("/<int:product_id>", methods=["DELETE"])
def delete_product(product_id):
    """Delete a product."""
    err = _check_api_key();
    if err: return err
    p = next((p for p in _STORE if p["id"] == product_id), None)
    if not p:
        return jsonify({"message": "Not found"}), 404
    _STORE.remove(p)
    return "", 204


@bp.route("/<int:product_id>/variants", methods=["GET"])
def get_variants(product_id):
    """List all variants of a product (nested resource)."""
    err = _check_api_key();
    if err: return err
    if not any(p["id"] == product_id for p in _STORE):
        return jsonify({"message": "Not found"}), 404
    return jsonify([
        {"id": 1, "product_id": product_id, "sku": f"SKU-{product_id}-SM-BLK", "size": "S", "color": "Black", "price_modifier": 0.0},
        {"id": 2, "product_id": product_id, "sku": f"SKU-{product_id}-MD-BLK", "size": "M", "color": "Black", "price_modifier": 0.0},
        {"id": 3, "product_id": product_id, "sku": f"SKU-{product_id}-LG-WHT", "size": "L", "color": "White", "price_modifier": 5.0},
    ]), 200


# Extra Flask scanner edge case: <string:name> type-prefixed param
@bp.route("/by-name/<string:name>", methods=["GET"])
def get_product_by_name(name):
    """
    Get product by name slug. <string:name> normalises to {name}.
    Tests PythonScanner Flask param normalisation for string type prefix.
    """
    err = _check_api_key();
    if err: return err
    p = next((p for p in _STORE if p["name"].lower().replace(" ", "-") == name.lower()), None)
    return (jsonify(p), 200) if p else (jsonify({"message": "Not found"}), 404)
