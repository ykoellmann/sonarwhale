using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using SonarwhaleTestApi.Models;

namespace SonarwhaleTestApi.Controllers;

/// <summary>
/// Products — API Key authentication via X-Api-Key header.
///
/// Scanner coverage (beyond UsersController):
///   [AcceptVerbs("GET", "HEAD")]    → multi-verb on single action
///   [HttpGet("~/api/products/all")] → tilde override (absolute route)
///   [HttpGet("search")]             → suffix-only route template
/// </summary>
[ApiController]
[Route("api/[controller]")]
public class ProductsController : ControllerBase
{
    private static readonly List<Product> Store = new()
    {
        new(1, "Laptop Pro 15",  "High-end developer laptop",  1299.99m, ProductCategory.Electronics, true,  12, DateTime.UtcNow.AddDays(-90)),
        new(2, "Mechanical Keyboard", "Clicky switches",        89.99m,  ProductCategory.Electronics, true,  45, DateTime.UtcNow.AddDays(-60)),
        new(3, "Clean Code",     "Book by Robert C. Martin",    34.99m,  ProductCategory.Books,        true,  200, DateTime.UtcNow.AddDays(-120)),
        new(4, "Coffee Beans",   "Ethiopian single origin",     18.50m,  ProductCategory.Food,         false, 0,  DateTime.UtcNow.AddDays(-10)),
    };

    private bool IsAuthorized() =>
        Request.Headers.TryGetValue("X-Api-Key", out var key) && key == "test-api-key-12345";

    // ── GET /api/products  ───────────────────────────────────────────────────

    /// <summary>List products with optional filters.</summary>
    [HttpGet]
    [ProducesResponseType(typeof(PagedResult<Product>), 200)]
    [ProducesResponseType(401)]
    public IActionResult GetAll(
        [FromQuery] ProductCategory? category = null,
        [FromQuery] decimal? minPrice = null,
        [FromQuery] decimal? maxPrice = null,
        [FromQuery] bool? inStock = null,
        [FromQuery] int page = 1,
        [FromQuery] int pageSize = 20)
    {
        if (!IsAuthorized()) return Unauthorized(new { message = "Invalid or missing X-Api-Key" });

        var q = Store.AsQueryable();
        if (category.HasValue) q = q.Where(p => p.Category == category.Value);
        if (minPrice.HasValue) q = q.Where(p => p.Price >= minPrice.Value);
        if (maxPrice.HasValue) q = q.Where(p => p.Price <= maxPrice.Value);
        if (inStock.HasValue)  q = q.Where(p => p.InStock == inStock.Value);
        var items = q.Skip((page - 1) * pageSize).Take(pageSize);
        return Ok(new PagedResult<Product>(items, page, pageSize, q.Count()));
    }

    // ── GET /api/products/search  ────────────────────────────────────────────
    // ↑ suffix-only route template — scanner must NOT merge with controller prefix naively

    /// <summary>Full-text product search.</summary>
    [HttpGet("search")]
    [ProducesResponseType(typeof(IEnumerable<Product>), 200)]
    [ProducesResponseType(401)]
    public IActionResult Search(
        [FromQuery] string q,
        [FromQuery] string[]? tags = null,
        [FromQuery] bool inStock = false)
    {
        if (!IsAuthorized()) return Unauthorized(new { message = "Invalid or missing X-Api-Key" });

        var results = Store.Where(p =>
            p.Name.Contains(q, StringComparison.OrdinalIgnoreCase) ||
            p.Description.Contains(q, StringComparison.OrdinalIgnoreCase));
        if (inStock) results = results.Where(p => p.InStock);
        return Ok(results);
    }

    // ── GET /api/products/{id}  ──────────────────────────────────────────────

    /// <summary>Get a product by ID. Also handles HEAD (returns headers only).</summary>
    [AcceptVerbs("GET", "HEAD")]
    [Route("{id:int}")]
    [ProducesResponseType(typeof(Product), 200)]
    [ProducesResponseType(404)]
    public IActionResult GetById([FromRoute] int id)
    {
        if (!IsAuthorized()) return Unauthorized();
        var product = Store.FirstOrDefault(p => p.Id == id);
        return product is null ? NotFound() : Ok(product);
    }

    // ── POST /api/products  ──────────────────────────────────────────────────

    /// <summary>Create a new product.</summary>
    [HttpPost]
    [ProducesResponseType(typeof(Product), 201)]
    [ProducesResponseType(400)]
    [ProducesResponseType(401)]
    public IActionResult Create([FromBody] CreateProductRequest request)
    {
        if (!IsAuthorized()) return Unauthorized();
        var product = new Product(Store.Max(p => p.Id) + 1, request.Name, request.Description,
            request.Price, request.Category, request.StockCount > 0, request.StockCount, DateTime.UtcNow);
        Store.Add(product);
        return CreatedAtAction(nameof(GetById), new { id = product.Id }, product);
    }

    // ── PUT /api/products/{id}  ──────────────────────────────────────────────

    /// <summary>Full update of a product.</summary>
    [HttpPut("{id:int}")]
    [ProducesResponseType(typeof(Product), 200)]
    [ProducesResponseType(404)]
    public IActionResult Update([FromRoute] int id, [FromBody] UpdateProductRequest request)
    {
        if (!IsAuthorized()) return Unauthorized();
        var idx = Store.FindIndex(p => p.Id == id);
        if (idx < 0) return NotFound();
        var existing = Store[idx];
        Store[idx] = existing with
        {
            Name        = request.Name        ?? existing.Name,
            Description = request.Description ?? existing.Description,
            Price       = request.Price       ?? existing.Price,
            Category    = request.Category    ?? existing.Category,
            StockCount  = request.StockCount  ?? existing.StockCount,
            InStock     = (request.StockCount ?? existing.StockCount) > 0,
        };
        return Ok(Store[idx]);
    }

    // ── DELETE /api/products/{id}  ───────────────────────────────────────────

    /// <summary>Delete a product.</summary>
    [HttpDelete("{id:int}")]
    [ProducesResponseType(204)]
    [ProducesResponseType(404)]
    public IActionResult Delete([FromRoute] int id)
    {
        if (!IsAuthorized()) return Unauthorized();
        var product = Store.FirstOrDefault(p => p.Id == id);
        if (product is null) return NotFound();
        Store.Remove(product);
        return NoContent();
    }

    // ── GET /api/products/{id}/variants  ────────────────────────────────────

    /// <summary>List all variants of a product (nested resource).</summary>
    [HttpGet("{id:int}/variants")]
    [ProducesResponseType(typeof(IEnumerable<ProductVariant>), 200)]
    [ProducesResponseType(404)]
    public IActionResult GetVariants([FromRoute] int id)
    {
        if (!IsAuthorized()) return Unauthorized();
        if (Store.All(p => p.Id != id)) return NotFound();
        var variants = new[]
        {
            new ProductVariant(1, id, $"SKU-{id}-SM-BLK", "S", "Black", 0m, 10),
            new ProductVariant(2, id, $"SKU-{id}-MD-BLK", "M", "Black", 0m, 5),
            new ProductVariant(3, id, $"SKU-{id}-LG-WHT", "L", "White", 5m, 3),
        };
        return Ok(variants);
    }

    // ── GET ~/api/products/all  (tilde override — absolute route) ────────────
    // Tests CSharpScanner tilde-override logic

    /// <summary>Tilde-override route test: returns all products ignoring controller prefix resolution.</summary>
    [HttpGet("~/api/products/all")]
    [ProducesResponseType(typeof(IEnumerable<Product>), 200)]
    public IActionResult GetAllAbsolute()
    {
        if (!IsAuthorized()) return Unauthorized();
        return Ok(Store);
    }

    // ── NonAction ────────────────────────────────────────────────────────────

    [NonAction]
    public bool ProductExists(int id) => Store.Any(p => p.Id == id);
}
