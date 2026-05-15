using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using SonarwhaleTestApi.Models;

namespace SonarwhaleTestApi.Controllers;

/// <summary>
/// Admin — Basic authentication required.
///
/// Scanner coverage:
///   [Area("admin")]         → area routing token resolution
///   [Route("api/admin")]    → explicit prefix (no [controller] token)
///   [Authorize(Roles="admin")] + Basic auth validated manually
/// </summary>
[ApiController]
[Area("admin")]
[Route("api/admin")]
public class AdminController : ControllerBase
{
    private static readonly List<AdminUser> Store = new()
    {
        new(1, "admin",    "admin@example.com",    "admin", true,  false, DateTime.UtcNow.AddDays(-30), DateTime.UtcNow.AddHours(-1)),
        new(2, "alice",    "alice@example.com",    "user",  true,  false, DateTime.UtcNow.AddDays(-10), DateTime.UtcNow.AddDays(-1)),
        new(3, "bob",      "bob@example.com",      "user",  true,  false, DateTime.UtcNow.AddDays(-5),  null),
        new(4, "badactor", "bad@example.com",      "user",  false, true,  DateTime.UtcNow.AddDays(-60), null),
    };

    private bool IsBasicAuth()
    {
        var auth = Request.Headers.Authorization.ToString();
        if (!auth.StartsWith("Basic ")) return false;
        try
        {
            var decoded = System.Text.Encoding.UTF8.GetString(Convert.FromBase64String(auth[6..]));
            return decoded == "admin:admin123";
        }
        catch { return false; }
    }

    // ── GET /api/admin/users  ────────────────────────────────────────────────

    /// <summary>Admin view of all users including banned accounts.</summary>
    [HttpGet("users")]
    [ProducesResponseType(typeof(IEnumerable<AdminUser>), 200)]
    [ProducesResponseType(401)]
    [ProducesResponseType(403)]
    public IActionResult GetUsers([FromQuery] bool includeBanned = true)
    {
        if (!IsBasicAuth()) return Unauthorized(new { message = "Basic auth required" });
        var users = includeBanned ? Store : Store.Where(u => !u.IsBanned);
        return Ok(users);
    }

    // ── POST /api/admin/users/{id}/ban  ─────────────────────────────────────

    /// <summary>Ban a user account.</summary>
    [HttpPost("users/{id:int}/ban")]
    [ProducesResponseType(200)]
    [ProducesResponseType(401)]
    [ProducesResponseType(404)]
    public IActionResult BanUser([FromRoute] int id, [FromBody] BanUserRequest request)
    {
        if (!IsBasicAuth()) return Unauthorized();
        var idx = Store.FindIndex(u => u.Id == id);
        if (idx < 0) return NotFound(new { message = $"User {id} not found" });
        Store[idx] = Store[idx] with { IsBanned = true, IsActive = false };
        return Ok(new { message = $"User {id} banned. Reason: {request.Reason}", banUntil = request.BanUntil });
    }

    // ── DELETE /api/admin/users/{id}  ────────────────────────────────────────

    /// <summary>Permanently delete a user (hard delete).</summary>
    [HttpDelete("users/{id:int}")]
    [ProducesResponseType(204)]
    [ProducesResponseType(401)]
    [ProducesResponseType(404)]
    public IActionResult DeleteUser([FromRoute] int id)
    {
        if (!IsBasicAuth()) return Unauthorized();
        var user = Store.FirstOrDefault(u => u.Id == id);
        if (user is null) return NotFound();
        Store.Remove(user);
        return NoContent();
    }

    // ── GET /api/admin/stats  ────────────────────────────────────────────────

    /// <summary>System statistics and usage metrics.</summary>
    [HttpGet("stats")]
    [ProducesResponseType(typeof(SystemStats), 200)]
    [ProducesResponseType(401)]
    public IActionResult GetStats()
    {
        if (!IsBasicAuth()) return Unauthorized();
        return Ok(new SystemStats(
            TotalUsers:    Store.Count,
            ActiveUsers:   Store.Count(u => u.IsActive),
            TotalProducts: 4,
            TotalOrders:   2,
            TotalRevenue:  1459.96m,
            GeneratedAt:   DateTime.UtcNow));
    }
}
