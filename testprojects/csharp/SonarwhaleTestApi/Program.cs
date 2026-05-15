using Microsoft.AspNetCore.Authentication.JwtBearer;
using Microsoft.IdentityModel.Tokens;
using Scalar.AspNetCore;
using System.Text;

var builder = WebApplication.CreateBuilder(args);

// ── Controllers ──────────────────────────────────────────────────────────────
builder.Services.AddControllers();

// ── JWT Bearer auth ──────────────────────────────────────────────────────────
const string JwtSecret = "sonarwhale-test-secret-key-32chars!";

builder.Services.AddAuthentication(JwtBearerDefaults.AuthenticationScheme)
    .AddJwtBearer(options =>
    {
        options.TokenValidationParameters = new TokenValidationParameters
        {
            ValidateIssuer           = true,
            ValidateAudience         = true,
            ValidateLifetime         = true,
            ValidateIssuerSigningKey = true,
            ValidIssuer              = "sonarwhale-test",
            ValidAudience            = "sonarwhale-test",
            IssuerSigningKey         = new SymmetricSecurityKey(Encoding.UTF8.GetBytes(JwtSecret)),
        };
    });

builder.Services.AddAuthorization();

// ── OpenAPI (.NET 10 native — spec at /openapi/v1.json) ──────────────────────
// Sonarwhale auto-discovery path: /openapi/v1.json (ASP.NET Core Microsoft.OpenApi)
builder.Services.AddOpenApi("v1");

// ── App ───────────────────────────────────────────────────────────────────────
var app = builder.Build();

// Serve spec at /openapi/v1.json (Sonarwhale auto-discovery: ASP.NET Core Microsoft.OpenApi path)
app.MapOpenApi();
// Scalar UI at /scalar/v1
app.MapScalarApiReference();

app.UseAuthentication();
app.UseAuthorization();
app.MapControllers();

// ── Minimal API endpoints ─────────────────────────────────────────────────────
// These test app.MapXxx() pattern matching in CSharpScanner.

// GET /health — no auth (tests inherit.off pattern in Sonarwhale scripts)
app.MapGet("/health", () => Results.Ok(new
{
    status    = "healthy",
    version   = "1.0.0",
    timestamp = DateTime.UtcNow,
}))
.AllowAnonymous()
.WithName("GetHealth")
.WithTags("Public")
.WithSummary("Health check — no auth required.");

// GET /api/version — no auth
app.MapGet("/api/version", () => Results.Ok(new
{
    version   = "1.0.0",
    commit    = "abc1234",
    builtAt   = "2026-01-01T00:00:00Z",
    framework = System.Runtime.InteropServices.RuntimeInformation.FrameworkDescription,
}))
.AllowAnonymous()
.WithName("GetVersion")
.WithTags("Public")
.WithSummary("API version information.");

// OPTIONS /api/users — CORS preflight (tests OPTIONS HTTP method in Sonarwhale)
app.MapMethods("/api/users", new[] { "OPTIONS" }, (HttpContext ctx) =>
{
    ctx.Response.Headers.Append("Allow", "GET, POST, OPTIONS, HEAD");
    ctx.Response.Headers.Append("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
    ctx.Response.Headers.Append("Access-Control-Allow-Headers", "Authorization, Content-Type, X-Api-Key");
    return Results.Ok();
})
.AllowAnonymous()
.WithName("OptionsUsers")
.WithTags("Public")
.WithSummary("CORS preflight — tests OPTIONS HTTP method.");

// HEAD /api/users/{id} — existence check without body (tests HEAD method)
app.MapMethods("/api/users/{id:int}", new[] { "HEAD" }, (int id) =>
    id is >= 1 and <= 100 ? Results.Ok() : Results.NotFound()
)
.AllowAnonymous()
.WithName("HeadUserById")
.WithTags("Public")
.WithSummary("Check if user exists (no response body — tests HEAD method).");

app.Run();
