"""
Products router — API Key auth (X-Api-Key header).

Scanner coverage (PythonScanner):
  @router.get("/")             → GET    /api/products
  @router.get("/search")       → GET    /api/products/search
  @router.get("/{product_id}") → GET    /api/products/{product_id}
  @router.post("/")            → POST   /api/products
  @router.put("/{product_id}") → PUT    /api/products/{product_id}
  @router.delete("/{product_id}") → DELETE /api/products/{product_id}
  @router.get("/{product_id}/variants") → GET /api/products/{product_id}/variants
"""
from datetime import datetime, timezone
from typing import List, Optional

from fastapi import APIRouter, Depends, HTTPException, Query

from models.models import (
    CreateProductRequest, PagedResult, Product, ProductCategory,
    ProductVariant, UpdateProductRequest,
)
from security import require_api_key

router = APIRouter(prefix="/api/products", tags=["Products"])

_STORE: List[Product] = [
    Product(id=1, name="Laptop Pro 15",      description="High-end developer laptop", price=1299.99, category=ProductCategory.electronics, in_stock=True,  stock_count=12, created_at=datetime(2025, 10, 1, tzinfo=timezone.utc)),
    Product(id=2, name="Mechanical Keyboard", description="Clicky switches",           price=89.99,   category=ProductCategory.electronics, in_stock=True,  stock_count=45, created_at=datetime(2025, 11, 1, tzinfo=timezone.utc)),
    Product(id=3, name="Clean Code",          description="Book by Robert C. Martin",  price=34.99,   category=ProductCategory.books,       in_stock=True,  stock_count=200, created_at=datetime(2025, 9, 1, tzinfo=timezone.utc)),
    Product(id=4, name="Coffee Beans",        description="Ethiopian single origin",    price=18.50,   category=ProductCategory.food,        in_stock=False, stock_count=0,   created_at=datetime(2026, 4, 1, tzinfo=timezone.utc)),
]


@router.get("/", response_model=PagedResult[Product])
def list_products(
    category: Optional[ProductCategory] = Query(default=None),
    min_price: Optional[float] = Query(default=None, ge=0),
    max_price: Optional[float] = Query(default=None, ge=0),
    in_stock: Optional[bool] = Query(default=None),
    page: int = Query(default=1, ge=1),
    page_size: int = Query(default=20, ge=1, le=100),
    _api_key: str = Depends(require_api_key),
):
    """List products with optional filters."""
    q = _STORE
    if category:    q = [p for p in q if p.category == category]
    if min_price is not None: q = [p for p in q if p.price >= min_price]
    if max_price is not None: q = [p for p in q if p.price <= max_price]
    if in_stock is not None:  q = [p for p in q if p.in_stock == in_stock]
    total = len(q)
    return PagedResult(items=q[(page - 1) * page_size : page * page_size],
                       page=page, page_size=page_size, total_count=total)


@router.get("/search", response_model=List[Product])
def search_products(
    q: str = Query(..., min_length=1, description="Search query"),
    tags: Optional[List[str]] = Query(default=None, description="Filter by tags"),
    in_stock: bool = Query(default=False),
    _api_key: str = Depends(require_api_key),
):
    """Full-text product search by name/description."""
    results = [p for p in _STORE if q.lower() in p.name.lower() or q.lower() in p.description.lower()]
    if in_stock:
        results = [p for p in results if p.in_stock]
    return results


@router.get("/{product_id}", response_model=Product)
def get_product(product_id: int, _api_key: str = Depends(require_api_key)):
    """Get a product by ID."""
    product = next((p for p in _STORE if p.id == product_id), None)
    if not product:
        raise HTTPException(status_code=404, detail=f"Product {product_id} not found")
    return product


@router.post("/", response_model=Product, status_code=201)
def create_product(
    request: CreateProductRequest,
    _api_key: str = Depends(require_api_key),
):
    """Create a new product."""
    product = Product(
        id=max(p.id for p in _STORE) + 1,
        name=request.name,
        description=request.description,
        price=request.price,
        category=request.category,
        in_stock=request.stock_count > 0,
        stock_count=request.stock_count,
        created_at=datetime.now(timezone.utc),
    )
    _STORE.append(product)
    return product


@router.put("/{product_id}", response_model=Product)
def update_product(
    product_id: int,
    request: UpdateProductRequest,
    _api_key: str = Depends(require_api_key),
):
    """Full update of a product."""
    idx = next((i for i, p in enumerate(_STORE) if p.id == product_id), None)
    if idx is None:
        raise HTTPException(status_code=404, detail=f"Product {product_id} not found")
    existing = _STORE[idx]
    updates = {k: v for k, v in request.model_dump().items() if v is not None}
    if "stock_count" in updates:
        updates["in_stock"] = updates["stock_count"] > 0
    _STORE[idx] = existing.model_copy(update=updates)
    return _STORE[idx]


@router.delete("/{product_id}", status_code=204)
def delete_product(product_id: int, _api_key: str = Depends(require_api_key)):
    """Delete a product."""
    product = next((p for p in _STORE if p.id == product_id), None)
    if not product:
        raise HTTPException(status_code=404, detail=f"Product {product_id} not found")
    _STORE.remove(product)
    return None


@router.get("/{product_id}/variants", response_model=List[ProductVariant])
def get_variants(product_id: int, _api_key: str = Depends(require_api_key)):
    """List all variants (sizes/colors) of a product."""
    if not any(p.id == product_id for p in _STORE):
        raise HTTPException(status_code=404, detail=f"Product {product_id} not found")
    return [
        ProductVariant(id=1, product_id=product_id, sku=f"SKU-{product_id}-SM-BLK", size="S", color="Black", price_modifier=0.0,  stock_count=10),
        ProductVariant(id=2, product_id=product_id, sku=f"SKU-{product_id}-MD-BLK", size="M", color="Black", price_modifier=0.0,  stock_count=5),
        ProductVariant(id=3, product_id=product_id, sku=f"SKU-{product_id}-LG-WHT", size="L", color="White", price_modifier=5.0,  stock_count=3),
    ]
