"""
Orders router — Bearer JWT auth (simulates OAuth2 client-credentials scope).

Scanner coverage (PythonScanner):
  @router.get("/")             → GET    /api/orders
  @router.get("/{order_id}")   → GET    /api/orders/{order_id}
  @router.post("/")            → POST   /api/orders   (complex nested body)
  @router.patch("/{order_id}/status") → PATCH /api/orders/{order_id}/status
  @router.delete("/{order_id}") → DELETE /api/orders/{order_id}
"""
from datetime import datetime, timezone
from typing import List, Optional

from fastapi import APIRouter, Depends, HTTPException, Query

from models.models import (
    CreateOrderRequest, Order, OrderItem, OrderStatus,
    ShippingAddress, UpdateOrderStatusRequest,
)
from security import get_current_user

router = APIRouter(prefix="/api/orders", tags=["Orders"])

_STORE: List[Order] = [
    Order(
        id=1, user_id=2,
        items=[
            OrderItem(product_id=1, product_name="Laptop Pro 15",      quantity=1, unit_price=1299.99),
            OrderItem(product_id=2, product_name="Mechanical Keyboard", quantity=1, unit_price=89.99),
        ],
        shipping_address=ShippingAddress(street="123 Main St", city="Springfield", postal_code="12345", country="US"),
        status=OrderStatus.delivered, total_amount=1389.98,
        created_at=datetime(2026, 3, 1, tzinfo=timezone.utc),
    ),
    Order(
        id=2, user_id=3,
        items=[OrderItem(product_id=3, product_name="Clean Code", quantity=2, unit_price=34.99)],
        shipping_address=ShippingAddress(street="456 Oak Ave", city="Shelbyville", postal_code="67890", country="US"),
        status=OrderStatus.shipped, total_amount=69.98,
        created_at=datetime(2026, 5, 10, tzinfo=timezone.utc),
    ),
]


@router.get("/", response_model=dict)
def list_orders(
    status: Optional[OrderStatus] = Query(default=None, description="Filter by order status"),
    from_date: Optional[datetime] = Query(default=None, alias="from", description="Created after this date"),
    to_date: Optional[datetime] = Query(default=None, alias="to", description="Created before this date"),
    user_id: Optional[int] = Query(default=None),
    page: int = Query(default=1, ge=1),
    page_size: int = Query(default=20, ge=1, le=100),
    current_user: dict = Depends(get_current_user),
):
    """List orders with optional filters. Requires Bearer JWT (OAuth2 scope: orders:read)."""
    q = _STORE
    if status:    q = [o for o in q if o.status == status]
    if user_id:   q = [o for o in q if o.user_id == user_id]
    if from_date: q = [o for o in q if o.created_at >= from_date]
    if to_date:   q = [o for o in q if o.created_at <= to_date]
    total = len(q)
    return {"items": q[(page - 1) * page_size : page * page_size],
            "page": page, "page_size": page_size, "total_count": total}


@router.get("/{order_id}", response_model=Order)
def get_order(order_id: int, current_user: dict = Depends(get_current_user)):
    """Get a single order including all line items."""
    order = next((o for o in _STORE if o.id == order_id), None)
    if not order:
        raise HTTPException(status_code=404, detail=f"Order {order_id} not found")
    return order


@router.post("/", response_model=Order, status_code=201)
def create_order(
    request: CreateOrderRequest,
    current_user: dict = Depends(get_current_user),
):
    """
    Create a new order. Demonstrates a complex nested request body:
    - items[]: array of {product_id, quantity}
    - shipping_address{}: nested object with street/city/postal_code/country
    """
    items = [
        OrderItem(product_id=i.product_id, product_name=f"Product {i.product_id}",
                  quantity=i.quantity, unit_price=10.0)
        for i in request.items
    ]
    total = sum(i.quantity * i.unit_price for i in items)
    user_id = int(current_user.get("sub", "1")) if current_user.get("sub", "").isdigit() else 1
    order = Order(
        id=max(o.id for o in _STORE) + 1,
        user_id=user_id,
        items=items,
        shipping_address=request.shipping_address,
        status=OrderStatus.pending,
        total_amount=total,
        created_at=datetime.now(timezone.utc),
    )
    _STORE.append(order)
    return order


@router.patch("/{order_id}/status", response_model=Order)
def update_order_status(
    order_id: int,
    request: UpdateOrderStatusRequest,
    current_user: dict = Depends(get_current_user),
):
    """Update the status of an existing order."""
    idx = next((i for i, o in enumerate(_STORE) if o.id == order_id), None)
    if idx is None:
        raise HTTPException(status_code=404, detail=f"Order {order_id} not found")
    if _STORE[idx].status == OrderStatus.cancelled:
        raise HTTPException(status_code=400, detail="Cannot update a cancelled order")
    _STORE[idx] = _STORE[idx].model_copy(update={"status": request.status})
    return _STORE[idx]


@router.delete("/{order_id}", status_code=204)
def cancel_order(order_id: int, current_user: dict = Depends(get_current_user)):
    """Cancel an order (soft-delete — sets status to cancelled). Returns 204."""
    idx = next((i for i, o in enumerate(_STORE) if o.id == order_id), None)
    if idx is None:
        raise HTTPException(status_code=404, detail=f"Order {order_id} not found")
    _STORE[idx] = _STORE[idx].model_copy(update={"status": OrderStatus.cancelled})
    return None
