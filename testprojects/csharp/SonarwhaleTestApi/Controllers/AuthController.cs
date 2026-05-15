using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.IdentityModel.Tokens;
using System.IdentityModel.Tokens.Jwt;
using System.Security.Claims;
using System.Text;
using SonarwhaleTestApi.Models;

namespace SonarwhaleTestApi.Controllers;

/// <summary>Authentication — no auth required on any of these endpoints.</summary>
[ApiController]
[Route("api/[controller]")]
[AllowAnonymous]
public class AuthController : ControllerBase
{
    private const string SecretKey = "sonarwhale-test-secret-key-32chars!";

    /// <summary>Login with username and password. Returns JWT access + refresh token.</summary>
    /// <response code="200">Login successful</response>
    /// <response code="401">Invalid credentials</response>
    [HttpPost("login")]
    [ProducesResponseType(typeof(TokenResponse), 200)]
    [ProducesResponseType(401)]
    public IActionResult Login([FromBody] LoginRequest request)
    {
        // Hardcoded test users
        var users = new Dictionary<string, (string password, string role)>
        {
            ["admin"]    = ("admin123",    "admin"),
            ["user"]     = ("user123",     "user"),
            ["readonly"] = ("readonly123", "readonly"),
        };

        if (!users.TryGetValue(request.Username, out var info) || info.password != request.Password)
            return Unauthorized(new { message = "Invalid credentials" });

        var token = GenerateToken(request.Username, info.role);
        return Ok(new TokenResponse(token, $"refresh_{request.Username}_{Guid.NewGuid()}", 3600));
    }

    /// <summary>Exchange a refresh token for a new access token.</summary>
    /// <response code="200">New tokens issued</response>
    /// <response code="401">Invalid or expired refresh token</response>
    [HttpPost("refresh")]
    [ProducesResponseType(typeof(TokenResponse), 200)]
    [ProducesResponseType(401)]
    public IActionResult Refresh([FromBody] RefreshRequest request)
    {
        if (string.IsNullOrWhiteSpace(request.RefreshToken) || !request.RefreshToken.StartsWith("refresh_"))
            return Unauthorized(new { message = "Invalid refresh token" });

        var parts = request.RefreshToken.Split('_');
        var username = parts.Length > 1 ? parts[1] : "user";
        var token = GenerateToken(username, "user");
        return Ok(new TokenResponse(token, $"refresh_{username}_{Guid.NewGuid()}", 3600));
    }

    /// <summary>Invalidate the current session / logout.</summary>
    /// <response code="204">Logged out successfully</response>
    [HttpPost("logout")]
    [ProducesResponseType(204)]
    public IActionResult Logout()
    {
        // In a real app: blacklist the token / clear the refresh token from DB
        return NoContent();
    }

    private static string GenerateToken(string username, string role)
    {
        var key = new SymmetricSecurityKey(Encoding.UTF8.GetBytes(SecretKey));
        var creds = new SigningCredentials(key, SecurityAlgorithms.HmacSha256);
        var claims = new[]
        {
            new Claim(ClaimTypes.Name, username),
            new Claim(ClaimTypes.Role, role),
            new Claim(JwtRegisteredClaimNames.Sub, username),
            new Claim(JwtRegisteredClaimNames.Jti, Guid.NewGuid().ToString()),
        };
        var jwt = new JwtSecurityToken(
            issuer: "sonarwhale-test",
            audience: "sonarwhale-test",
            claims: claims,
            expires: DateTime.UtcNow.AddHours(1),
            signingCredentials: creds);
        return new JwtSecurityTokenHandler().WriteToken(jwt);
    }
}
