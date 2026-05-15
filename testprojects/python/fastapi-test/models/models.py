from __future__ import annotations
from datetime import datetime
from enum import Enum
from typing import Generic, List, Optional, TypeVar

from pydantic import BaseModel, EmailStr, Field

T = TypeVar("T")


# ── Auth ─────────────────────────────────────────────────────────────────────

class LoginRequest(BaseModel):
    username: str = Field(..., example="admin")
    password: str = Field(..., example="admin123")

class RefreshRequest(BaseModel):
    refresh_token: str

class TokenResponse(BaseModel):
    access_token: str
    refresh_token: str
    token_type: str = "bearer"
    expires_in: int = 3600


# ── Users ─────────────────────────────────────────────────────────────────────

class UserRole(str, Enum):
    admin = "admin"
    user = "user"
    readonly = "readonly"

class User(BaseModel):
    id: int
    username: str
    email: str
    role: UserRole
    is_active: bool
    created_at: datetime

class CreateUserRequest(BaseModel):
    username: str = Field(..., min_length=3, max_length=50)
    email: str
    password: str = Field(..., min_length=8)
    role: UserRole = UserRole.user

class UpdateUserRequest(BaseModel):
    username: Optional[str] = None
    email: Optional[str] = None
    role: Optional[UserRole] = None

class PagedResult(BaseModel, Generic[T]):
    items: List[T]
    page: int
    page_size: int
    total_count: int


# ── Products ──────────────────────────────────────────────────────────────────

class ProductCategory(str, Enum):
    electronics = "electronics"
    clothing = "clothing"
    food = "food"
    books = "books"
    other = "other"

class Product(BaseModel):
    id: int
    name: str
    description: str
    price: float
    category: ProductCategory
    in_stock: bool
    stock_count: int
    created_at: datetime

class CreateProductRequest(BaseModel):
    name: str = Field(..., min_length=1, max_length=200)
    description: str
    price: float = Field(..., gt=0)
    category: ProductCategory
    stock_count: int = Field(..., ge=0)

class UpdateProductRequest(BaseModel):
    name: Optional[str] = None
    description: Optional[str] = None
    price: Optional[float] = Field(default=None, gt=0)
    category: Optional[ProductCategory] = None
    stock_count: Optional[int] = Field(default=None, ge=0)

class ProductVariant(BaseModel):
    id: int
    product_id: int
    sku: str
    size: str
    color: str
    price_modifier: float
    stock_count: int


# ── Orders ────────────────────────────────────────────────────────────────────

class OrderStatus(str, Enum):
    pending = "pending"
    confirmed = "confirmed"
    shipped = "shipped"
    delivered = "delivered"
    cancelled = "cancelled"

class OrderItem(BaseModel):
    product_id: int
    product_name: str
    quantity: int
    unit_price: float

class ShippingAddress(BaseModel):
    street: str
    city: str
    postal_code: str
    country: str

class Order(BaseModel):
    id: int
    user_id: int
    items: List[OrderItem]
    shipping_address: ShippingAddress
    status: OrderStatus
    total_amount: float
    created_at: datetime

class CreateOrderItemRequest(BaseModel):
    product_id: int
    quantity: int = Field(..., ge=1)

class CreateOrderRequest(BaseModel):
    items: List[CreateOrderItemRequest] = Field(..., min_length=1)
    shipping_address: ShippingAddress

class UpdateOrderStatusRequest(BaseModel):
    status: OrderStatus


# ── Admin ─────────────────────────────────────────────────────────────────────

class AdminUser(BaseModel):
    id: int
    username: str
    email: str
    role: UserRole
    is_active: bool
    is_banned: bool
    created_at: datetime
    last_login_at: Optional[datetime] = None

class BanUserRequest(BaseModel):
    reason: str
    ban_until: Optional[datetime] = None

class SystemStats(BaseModel):
    total_users: int
    active_users: int
    total_products: int
    total_orders: int
    total_revenue: float
    generated_at: datetime
