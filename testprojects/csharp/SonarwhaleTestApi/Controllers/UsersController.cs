using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using SonarwhaleTestApi.Models;

namespace SonarwhaleTestApi.Controllers;

/// <summary>
/// Users — Bearer JWT auth required.
///
/// Scanner coverage:
///   [HttpGet]                     → no template  → controller-prefix match
///   [HttpGet("{id:int}")]         → path param with int constraint
///   [HttpGet("{id:int}/avatar")]  → nested sub-resource
///   [HttpPost]                    → no template
///   [HttpPut("{id:int}")]         → path + body
///   [HttpPatch("{id:int}")]       → partial update
///   [HttpDelete("{id:int}")]      → 204 No Content
///   [HttpPost("{id:int}/avatar")] → multipart/form-data
///   [NonAction]                   → must NOT produce a gutter icon
/// </summary>
[ApiController]
[Route("api/[controller]")]
[Authorize]
public class UsersController : ControllerBase
{
    private static readonly List<User> Store = new()
    {
        new(1, "admin",    "admin@example.com",    "admin",    true, DateTime.UtcNow.AddDays(-30)),
        new(2, "alice",    "alice@example.com",    "user",     true, DateTime.UtcNow.AddDays(-10)),
        new(3, "bob",      "bob@example.com",      "user",     true, DateTime.UtcNow.AddDays(-5)),
        new(4, "inactive", "inactive@example.com", "user",     false, DateTime.UtcNow.AddDays(-60)),
    };

    // ── GET /api/users  ──────────────────────────────────────────────────────

    /// <summary>List all users (paginated).</summary>
    /// <param name="page">Page number (1-based)</param>
    /// <param name="pageSize">Items per page (max 100)</param>
    /// <param name="search">Filter by username or email</param>
    /// <param name="sort">Sort field: username | email | createdAt</param>
    [HttpGet]
    [ProducesResponseType(typeof(PagedResult<User>), 200)]
    public IActionResult GetAll(
        [FromQuery] int page = 1,
        [FromQuery] int pageSize = 20,
        [FromQuery] string? search = null,
        [FromQuery] string sort = "username")
    {
        var q = Store.AsQueryable();
        if (!string.IsNullOrWhiteSpace(search))
            q = q.Where(u => u.Username.Contains(search, StringComparison.OrdinalIgnoreCase)
                          || u.Email.Contains(search, StringComparison.OrdinalIgnoreCase));
        var items = q.Skip((page - 1) * pageSize).Take(pageSize);
        return Ok(new PagedResult<User>(items, page, pageSize, q.Count()));
    }

    // ── GET /api/users/{id}  ─────────────────────────────────────────────────

    /// <summary>Get a single user by ID.</summary>
    /// <param name="id">User ID</param>
    [HttpGet("{id:int}")]
    [ProducesResponseType(typeof(User), 200)]
    [ProducesResponseType(404)]
    public IActionResult GetById([FromRoute] int id)
    {
        var user = Store.FirstOrDefault(u => u.Id == id);
        return user is null ? NotFound(new { message = $"User {id} not found" }) : Ok(user);
    }

    // ── POST /api/users  ─────────────────────────────────────────────────────

    /// <summary>Create a new user.</summary>
    [HttpPost]
    [ProducesResponseType(typeof(User), 201)]
    [ProducesResponseType(typeof(ValidationProblemDetails), 400)]
    [ProducesResponseType(409)]
    public IActionResult Create([FromBody] CreateUserRequest request)
    {
        if (Store.Any(u => u.Username == request.Username))
            return Conflict(new { message = $"Username '{request.Username}' already taken" });

        var user = new User(Store.Max(u => u.Id) + 1, request.Username,
            request.Email, request.Role, true, DateTime.UtcNow);
        Store.Add(user);
        return CreatedAtAction(nameof(GetById), new { id = user.Id }, user);
    }

    // ── PUT /api/users/{id}  ─────────────────────────────────────────────────

    /// <summary>Full update of a user (all fields required).</summary>
    [HttpPut("{id:int}")]
    [ProducesResponseType(typeof(User), 200)]
    [ProducesResponseType(404)]
    public IActionResult Update([FromRoute] int id, [FromBody] UpdateUserRequest request)
    {
        var idx = Store.FindIndex(u => u.Id == id);
        if (idx < 0) return NotFound(new { message = $"User {id} not found" });

        var existing = Store[idx];
        Store[idx] = existing with
        {
            Username = request.Username ?? existing.Username,
            Email    = request.Email    ?? existing.Email,
            Role     = request.Role     ?? existing.Role,
        };
        return Ok(Store[idx]);
    }

    // ── PATCH /api/users/{id}  ───────────────────────────────────────────────

    /// <summary>Partial update — only provided fields are changed.</summary>
    [HttpPatch("{id:int}")]
    [ProducesResponseType(typeof(User), 200)]
    [ProducesResponseType(404)]
    public IActionResult PartialUpdate([FromRoute] int id, [FromBody] UpdateUserRequest request)
    {
        // Same logic as PUT for this demo; in production you'd use JsonPatchDocument
        return Update(id, request);
    }

    // ── DELETE /api/users/{id}  ──────────────────────────────────────────────

    /// <summary>Delete a user permanently.</summary>
    [HttpDelete("{id:int}")]
    [ProducesResponseType(204)]
    [ProducesResponseType(404)]
    public IActionResult Delete([FromRoute] int id)
    {
        var user = Store.FirstOrDefault(u => u.Id == id);
        if (user is null) return NotFound(new { message = $"User {id} not found" });
        Store.Remove(user);
        return NoContent();
    }

    // ── GET /api/users/{id}/avatar  ──────────────────────────────────────────

    /// <summary>Download a user's avatar image.</summary>
    /// <param name="id">User ID</param>
    [HttpGet("{id:int}/avatar")]
    [ProducesResponseType(typeof(FileResult), 200)]
    [ProducesResponseType(404)]
    public IActionResult GetAvatar(
        [FromRoute] int id,
        [FromHeader(Name = "Accept")] string? accept)
    {
        var user = Store.FirstOrDefault(u => u.Id == id);
        if (user is null) return NotFound();
        // Return a 1x1 transparent PNG as placeholder
        var png = Convert.FromBase64String("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==");
        return File(png, "image/png", $"avatar_{id}.png");
    }

    // ── POST /api/users/{id}/avatar  ─────────────────────────────────────────

    /// <summary>Upload a new avatar for a user (multipart/form-data).</summary>
    [HttpPost("{id:int}/avatar")]
    [Consumes("multipart/form-data")]
    [ProducesResponseType(200)]
    [ProducesResponseType(400)]
    [ProducesResponseType(404)]
    public async Task<IActionResult> UploadAvatar(
        [FromRoute] int id,
        IFormFile file)
    {
        var user = Store.FirstOrDefault(u => u.Id == id);
        if (user is null) return NotFound();
        if (file is null || file.Length == 0) return BadRequest(new { message = "No file provided" });
        if (!file.ContentType.StartsWith("image/")) return BadRequest(new { message = "File must be an image" });
        // In a real app: save to blob storage
        await Task.Delay(1); // simulate async work
        return Ok(new { message = "Avatar uploaded", size = file.Length, contentType = file.ContentType });
    }

    // ── NonAction helper — must NOT get a gutter icon ────────────────────────

    [NonAction]
    public User? FindUserByEmail(string email) => Store.FirstOrDefault(u => u.Email == email);
}
