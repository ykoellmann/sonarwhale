namespace SonarwhaleTestApi.Models;

// ── Auth ────────────────────────────────────────────────────────────────────

public record LoginRequest(string Username, string Password);
public record RefreshRequest(string RefreshToken);
public record TokenResponse(string AccessToken, string RefreshToken, int ExpiresIn);

// ── Users ───────────────────────────────────────────────────────────────────

public record User(int Id, string Username, string Email, string Role, bool IsActive, DateTime CreatedAt);

public record CreateUserRequest(string Username, string Email, string Password, string Role = "user");

public record UpdateUserRequest(string? Username, string? Email, string? Role);

public record PagedResult<T>(IEnumerable<T> Items, int Page, int PageSize, int TotalCount);

// ── Products ─────────────────────────────────────────────────────────────────

public enum ProductCategory { Electronics, Clothing, Food, Books, Other }

public record Product(int Id, string Name, string Description, decimal Price,
    ProductCategory Category, bool InStock, int StockCount, DateTime CreatedAt);

public record CreateProductRequest(string Name, string Description, decimal Price,
    ProductCategory Category, int StockCount);

public record UpdateProductRequest(string? Name, string? Description, decimal? Price,
    ProductCategory? Category, int? StockCount);

public record ProductVariant(int Id, int ProductId, string Sku, string Size,
    string Color, decimal PriceModifier, int StockCount);

// ── Orders ───────────────────────────────────────────────────────────────────

public enum OrderStatus { Pending, Confirmed, Shipped, Delivered, Cancelled }

public record OrderItem(int ProductId, string ProductName, int Quantity, decimal UnitPrice);

public record ShippingAddress(string Street, string City, string PostalCode, string Country);

public record Order(int Id, int UserId, IEnumerable<OrderItem> Items,
    ShippingAddress ShippingAddress, OrderStatus Status, decimal TotalAmount, DateTime CreatedAt);

public record CreateOrderRequest(IEnumerable<CreateOrderItemRequest> Items, ShippingAddress ShippingAddress);

public record CreateOrderItemRequest(int ProductId, int Quantity);

public record UpdateOrderStatusRequest(OrderStatus Status);

// ── Admin ────────────────────────────────────────────────────────────────────

public record AdminUser(int Id, string Username, string Email, string Role,
    bool IsActive, bool IsBanned, DateTime CreatedAt, DateTime? LastLoginAt);

public record BanUserRequest(string Reason, DateTime? BanUntil);

public record SystemStats(int TotalUsers, int ActiveUsers, int TotalProducts,
    int TotalOrders, decimal TotalRevenue, DateTime GeneratedAt);
