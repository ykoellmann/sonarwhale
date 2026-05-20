package com.sonarwhale.gutter

import com.sonarwhale.model.ApiEndpoint
import com.sonarwhale.model.EndpointSource
import com.sonarwhale.model.HttpMethod
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CSharpScannerTest {

    private val scanner = CSharpScanner()

    private fun endpoint(method: HttpMethod, path: String) = ApiEndpoint(
        id = "${method.name} $path",
        method = method,
        path = path,
        summary = null,
        tags = emptyList(),
        parameters = emptyList(),
        requestBody = null,
        responses = emptyMap(),
        auth = null,
        source = EndpointSource.OPENAPI_SERVER,
        psiNavigationTarget = null
    )

    // ── 1. HttpGet attribute matches GET endpoint ──────────────────────────────

    @Test
    fun `HttpGet attribute matches GET endpoint`() {
        val lines = listOf(
            """    [HttpGet("api/users")]""",
            """    public IActionResult GetUsers() { }"""
        )
        val endpoints = listOf(endpoint(HttpMethod.GET, "/api/users"))
        val matches = scanner.scanLines(lines, endpoints)

        assertEquals(1, matches.size)
        assertEquals(HttpMethod.GET, matches[0].endpoint.method)
        assertEquals("/api/users", matches[0].endpoint.path)
    }

    // ── 2. HttpPost matches POST but not GET ───────────────────────────────────

    @Test
    fun `HttpPost attribute matches POST but not GET`() {
        val lines = listOf(
            """    [HttpPost("api/users")]""",
            """    public IActionResult CreateUser() { }"""
        )
        val getEndpoint  = endpoint(HttpMethod.GET,  "/api/users")
        val postEndpoint = endpoint(HttpMethod.POST, "/api/users")
        val matches = scanner.scanLines(lines, listOf(getEndpoint, postEndpoint))

        assertEquals(1, matches.size)
        assertEquals(HttpMethod.POST, matches[0].endpoint.method)
    }

    // ── 3. [Route] + class name resolves [controller] prefix ──────────────────

    @Test
    fun `Route attribute with controller token resolves to controller name`() {
        val lines = listOf(
            """[Route("api/[controller]")]""",
            """public class UsersController : ControllerBase""",
            """{""",
            """    [HttpGet]""",
            """    public IActionResult GetAll() { }""",
            """}"""
        )
        val ep = endpoint(HttpMethod.GET, "/api/users")
        val matches = scanner.scanLines(lines, listOf(ep))

        assertEquals(1, matches.size)
        assertEquals("/api/users", matches[0].endpoint.path)
    }

    // ── 4. [controller] token is lowercased ───────────────────────────────────

    @Test
    fun `controller token is lowercased when building prefix`() {
        val lines = listOf(
            """[Route("api/[controller]")]""",
            """public class ProductsController : ControllerBase""",
            """{""",
            """    [HttpGet("list")]""",
            """    public IActionResult List() { }""",
            """}"""
        )
        val ep = endpoint(HttpMethod.GET, "/api/products/list")
        val matches = scanner.scanLines(lines, listOf(ep))

        assertEquals(1, matches.size)
        assertEquals("/api/products/list", matches[0].endpoint.path)
    }

    // ── 5. Minimal API MapGet matches ─────────────────────────────────────────

    @Test
    fun `MapGet minimal API call matches GET endpoint`() {
        val lines = listOf(
            """app.MapGet("/api/users", async (AppDb db) => await db.Users.ToListAsync());"""
        )
        val ep = endpoint(HttpMethod.GET, "/api/users")
        val matches = scanner.scanLines(lines, listOf(ep))

        assertEquals(1, matches.size)
        assertEquals(HttpMethod.GET, matches[0].endpoint.method)
        assertEquals("/api/users", matches[0].endpoint.path)
    }

    // ── 6. MapPost with path parameter matches ────────────────────────────────

    @Test
    fun `MapPost with path parameter matches endpoint`() {
        val lines = listOf(
            """app.MapPost("/api/users/{id}", async (int id, AppDb db) => { });"""
        )
        val ep = endpoint(HttpMethod.POST, "/api/users/{id}")
        val matches = scanner.scanLines(lines, listOf(ep))

        assertEquals(1, matches.size)
        assertEquals(HttpMethod.POST, matches[0].endpoint.method)
        assertEquals("/api/users/{id}", matches[0].endpoint.path)
    }

    // ── 7. [NonAction] excludes the method ────────────────────────────────────

    @Test
    fun `NonAction attribute excludes method from results`() {
        // [HttpGet] must come first so the scanner picks up the attribute block
        // starting from that line; [NonAction] follows in the same block.
        val lines = listOf(
            """    [HttpGet("api/users")]""",
            """    [NonAction]""",
            """    public IActionResult GetUsers() { }"""
        )
        val ep = endpoint(HttpMethod.GET, "/api/users")
        val matches = scanner.scanLines(lines, listOf(ep))

        assertTrue(matches.isEmpty(), "Expected no matches because of [NonAction]")
    }

    // ── 8. Route constraint {id:guid} normalised to {id} ─────────────────────

    @Test
    fun `route parameter constraint is normalised for matching`() {
        val lines = listOf(
            """    [HttpGet("api/items/{id:guid}")]""",
            """    public IActionResult Get(Guid id) { }"""
        )
        val ep = endpoint(HttpMethod.GET, "/api/items/{id}")
        val matches = scanner.scanLines(lines, listOf(ep))

        assertEquals(1, matches.size)
        assertEquals("/api/items/{id}", matches[0].endpoint.path)
    }

    // ── 9. Tilde override ignores controller prefix ───────────────────────────

    @Test
    fun `tilde override ignores controller route prefix`() {
        val lines = listOf(
            """[Route("api/[controller]")]""",
            """public class UsersController : ControllerBase""",
            """{""",
            """    [HttpGet("~/api/special")]""",
            """    public IActionResult Special() { }""",
            """}"""
        )
        val special = endpoint(HttpMethod.GET, "/api/special")
        val regular  = endpoint(HttpMethod.GET, "/api/users/special")
        val matches = scanner.scanLines(lines, listOf(special, regular))

        assertEquals(1, matches.size)
        assertEquals("/api/special", matches[0].endpoint.path)
    }

    // ── 10. AcceptVerbs matches at least one of the listed methods ────────────

    @Test
    fun `AcceptVerbs matches at least one of the listed HTTP methods`() {
        // The scanner returns one ScanMatch per declaration line (deduped by design).
        // AcceptVerbs("GET", "POST") resolves to the first matching endpoint found.
        val lines = listOf(
            """    [AcceptVerbs("GET", "POST")]""",
            """    [Route("api/users")]""",
            """    public IActionResult Handle() { }"""
        )
        val getEp  = endpoint(HttpMethod.GET,  "/api/users")
        val postEp = endpoint(HttpMethod.POST, "/api/users")
        val matches = scanner.scanLines(lines, listOf(getEp, postEp))

        // At least one match must be present and its method must be GET or POST
        assertEquals(1, matches.size, "Expected exactly one match (deduped per declaration line)")
        assertTrue(
            matches[0].endpoint.method == HttpMethod.GET || matches[0].endpoint.method == HttpMethod.POST,
            "Expected GET or POST match from AcceptVerbs"
        )
    }

    // ── 11. Empty endpoint list returns empty result ───────────────────────────

    @Test
    fun `empty endpoint list returns empty result`() {
        val lines = listOf(
            """    [HttpGet("api/users")]""",
            """    public IActionResult GetUsers() { }"""
        )
        val matches = scanner.scanLines(lines, emptyList())

        assertTrue(matches.isEmpty())
    }

    // ── 12. No verb lines returns empty result ────────────────────────────────

    @Test
    fun `no verb lines returns empty result`() {
        val lines = listOf(
            """    public IActionResult GetUsers() { }""",
            """    private void Helper() { }"""
        )
        val ep = endpoint(HttpMethod.GET, "/api/users")
        val matches = scanner.scanLines(lines, listOf(ep))

        assertTrue(matches.isEmpty())
    }

    // ── 13. Declaration line index is 0-based and correct ────────────────────

    @Test
    fun `declaration line index is zero-based and points to method declaration`() {
        val lines = listOf(
            """[HttpGet("api/users")]""",         // line 0
            """public IActionResult GetUsers()""" // line 1 — declaration
        )
        val ep = endpoint(HttpMethod.GET, "/api/users")
        val matches = scanner.scanLines(lines, listOf(ep))

        assertEquals(1, matches.size)
        assertEquals(1, matches[0].line)
    }

    // ── Additional: multiple endpoints in file produces multiple matches ───────

    @Test
    fun `multiple verb attributes in file produce multiple distinct matches`() {
        val lines = listOf(
            """    [HttpGet("api/users")]""",
            """    public IActionResult GetAll() { }""",
            """    [HttpPost("api/users")]""",
            """    public IActionResult Create() { }"""
        )
        val endpoints = listOf(
            endpoint(HttpMethod.GET,  "/api/users"),
            endpoint(HttpMethod.POST, "/api/users")
        )
        val matches = scanner.scanLines(lines, endpoints)

        assertEquals(2, matches.size)
        val methods = matches.map { it.endpoint.method }.toSet()
        assertTrue(HttpMethod.GET  in methods)
        assertTrue(HttpMethod.POST in methods)
    }

    // ── Additional: MapGet line index is correct ──────────────────────────────

    @Test
    fun `minimal API match line index points to the MapGet call`() {
        val lines = listOf(
            """var builder = WebApplication.CreateBuilder(args);""", // line 0
            """var app = builder.Build();""",                         // line 1
            """app.MapGet("/api/ping", () => "pong");"""             // line 2
        )
        val ep = endpoint(HttpMethod.GET, "/api/ping")
        val matches = scanner.scanLines(lines, listOf(ep))

        assertEquals(1, matches.size)
        assertEquals(2, matches[0].line)
    }

    // ── Additional: optional route param {id?} normalised ────────────────────

    @Test
    fun `optional route parameter is normalised for matching`() {
        val lines = listOf(
            """    [HttpGet("api/items/{id?}")]""",
            """    public IActionResult Get(int? id) { }"""
        )
        val ep = endpoint(HttpMethod.GET, "/api/items/{id}")
        val matches = scanner.scanLines(lines, listOf(ep))

        assertEquals(1, matches.size)
    }
}
