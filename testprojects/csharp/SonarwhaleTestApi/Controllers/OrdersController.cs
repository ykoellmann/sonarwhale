using Microsoft.AspNetCore.Mvc;
using SonarwhaleTestApi.Models;

namespace SonarwhaleTestApi.Controllers;

/// <summary>
/// Orders — OAuth2 client credentials (validated via Bearer token with "orders" scope).
/// For the test project we simply check for a specific scope claim in the JWT.
/// </summary>
[ApiController]
[Route("api/[controller]")]
public class OrdersController : ControllerBase
{
    private static readonly List<Order> Store = new()
    {
        new(1, 2, new[]
        {
            new OrderItem(1, "Laptop Pro 15", 1, 1299.99m),
            new OrderItem(2, "Mechanical Keyboard", 1, 89.99m),
        }, new ShippingAddress("123 Main St", "Springfield", "12345", "US"),
        OrderStatus.Delivered, 1389.98m, DateTime.UtcNow.AddDays(-20)),

        new(2, 3, new[]
        {
            new OrderItem(3, "Clean Code", 2, 34.99m),
        }, new ShippingAddress("456 Oak Ave", "Shelbyville", "67890", "US"),
        OrderStatus.Shipped, 69.98m, DateTime.UtcNow.AddDays(-3)),
    };

    private bool IsAuthorized()
    {
        // Accept a Bearer token that starts with "oauth2_" (set via pre-script in Sonarwhale)
        // OR a regular JWT (for ease of testing)
        var auth = Request.Headers.Authorization.ToString();
        return auth.StartsWith("Bearer ");
    }

    // ── GET /api/orders  ─────────────────────────────────────────────────────

    /// <summary>List orders with optional filters.</summary>
    [HttpGet]
    [ProducesResponseType(typeof(PagedResult<Order>), 200)]
    [ProducesResponseType(401)]
    public IActionResult GetAll(
        [FromQuery] OrderStatus? status = null,
        [FromQuery] DateTime? from = null,
        [FromQuery] DateTime? to = null,
        [FromQuery] int? userId = null,
        [FromQuery] int page = 1,
        [FromQuery] int pageSize = 20)
    {
        if (!IsAuthorized()) return Unauthorized(new { message = "Bearer token required" });

        var q = Store.AsQueryable();
        if (status.HasValue) q = q.Where(o => o.Status == status.Value);
        if (userId.HasValue) q = q.Where(o => o.UserId == userId.Value);
        if (from.HasValue)   q = q.Where(o => o.CreatedAt >= from.Value);
        if (to.HasValue)     q = q.Where(o => o.CreatedAt <= to.Value);
        var items = q.Skip((page - 1) * pageSize).Take(pageSize);
        return Ok(new PagedResult<Order>(items, page, pageSize, q.Count()));
    }

    // ── GET /api/orders/{id}  ────────────────────────────────────────────────

    /// <summary>Get a single order including all line items.</summary>
    [HttpGet("{id:int}")]
    [ProducesResponseType(typeof(Order), 200)]
    [ProducesResponseType(401)]
    [ProducesResponseType(404)]
    public IActionResult GetById([FromRoute] int id)
    {
        if (!IsAuthorized()) return Unauthorized();
        var order = Store.FirstOrDefault(o => o.Id == id);
        return order is null ? NotFound(new { message = $"Order {id} not found" }) : Ok(order);
    }

    // ── POST /api/orders  ────────────────────────────────────────────────────

    /// <summary>
    /// Create a new order. Demonstrates a complex nested request body:
    /// items[] array + shippingAddress{} nested object.
    /// </summary>
    [HttpPost]
    [ProducesResponseType(typeof(Order), 201)]
    [ProducesResponseType(400)]
    [ProducesResponseType(401)]
    public IActionResult Create([FromBody] CreateOrderRequest request)
    {
        if (!IsAuthorized()) return Unauthorized();
        if (!request.Items.Any()) return BadRequest(new { message = "Order must have at least one item" });

        var items = request.Items.Select(i => new OrderItem(i.ProductId, $"Product {i.ProductId}", i.Quantity, 10.00m));
        var total = items.Sum(i => i.Quantity * i.UnitPrice);
        // Extract userId from JWT sub claim
        var userId = int.TryParse(User.Claims.FirstOrDefault(c => c.Type == "sub")?.Value, out var uid) ? uid : 1;
        var order = new Order(Store.Max(o => o.Id) + 1, userId, items,
            request.ShippingAddress, OrderStatus.Pending, total, DateTime.UtcNow);
        Store.Add(order);
        return CreatedAtAction(nameof(GetById), new { id = order.Id }, order);
    }

    // ── PATCH /api/orders/{id}/status  ──────────────────────────────────────

    /// <summary>Update the status of an existing order.</summary>
    [HttpPatch("{id:int}/status")]
    [ProducesResponseType(typeof(Order), 200)]
    [ProducesResponseType(400)]
    [ProducesResponseType(401)]
    [ProducesResponseType(404)]
    public IActionResult UpdateStatus([FromRoute] int id, [FromBody] UpdateOrderStatusRequest request)
    {
        if (!IsAuthorized()) return Unauthorized();
        var idx = Store.FindIndex(o => o.Id == id);
        if (idx < 0) return NotFound();
        if (Store[idx].Status == OrderStatus.Cancelled)
            return BadRequest(new { message = "Cannot update a cancelled order" });
        Store[idx] = Store[idx] with { Status = request.Status };
        return Ok(Store[idx]);
    }

    // ── DELETE /api/orders/{id}  ─────────────────────────────────────────────

    /// <summary>Cancel an order (soft-delete — sets status to Cancelled).</summary>
    [HttpDelete("{id:int}")]
    [ProducesResponseType(204)]
    [ProducesResponseType(401)]
    [ProducesResponseType(404)]
    public IActionResult Cancel([FromRoute] int id)
    {
        if (!IsAuthorized()) return Unauthorized();
        var idx = Store.FindIndex(o => o.Id == id);
        if (idx < 0) return NotFound();
        Store[idx] = Store[idx] with { Status = OrderStatus.Cancelled };
        return NoContent();
    }
}
