package com.sonarwhale.gutter

import com.sonarwhale.model.ApiEndpoint
import com.sonarwhale.model.EndpointSource
import com.sonarwhale.model.HttpMethod
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PythonScannerTest {

    private val scanner = PythonScanner()

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

    // ── 1. FastAPI @app.get matches GET ───────────────────────────────────────

    @Test
    fun `FastAPI app get decorator matches GET endpoint`() {
        val lines = listOf(
            """@app.get("/api/users")""",
            """async def get_users():"""
        )
        val ep = endpoint(HttpMethod.GET, "/api/users")
        val matches = scanner.scanLines(lines, listOf(ep))

        assertEquals(1, matches.size)
        assertEquals(HttpMethod.GET, matches[0].endpoint.method)
        assertEquals("/api/users", matches[0].endpoint.path)
    }

    // ── 2. FastAPI @router.post matches POST ──────────────────────────────────

    @Test
    fun `FastAPI router post decorator matches POST endpoint`() {
        val lines = listOf(
            """@router.post("/api/users")""",
            """async def create_user():"""
        )
        val ep = endpoint(HttpMethod.POST, "/api/users")
        val matches = scanner.scanLines(lines, listOf(ep))

        assertEquals(1, matches.size)
        assertEquals(HttpMethod.POST, matches[0].endpoint.method)
    }

    // ── 3. FastAPI path parameter {user_id} matches ───────────────────────────

    @Test
    fun `FastAPI path parameter matches OpenAPI endpoint`() {
        val lines = listOf(
            """@router.get("/api/users/{user_id}")""",
            """async def get_user(user_id: int):"""
        )
        val ep = endpoint(HttpMethod.GET, "/api/users/{user_id}")
        val matches = scanner.scanLines(lines, listOf(ep))

        assertEquals(1, matches.size)
        assertEquals("/api/users/{user_id}", matches[0].endpoint.path)
    }

    // ── 4. Flask @app.route with methods=["GET"] ──────────────────────────────

    @Test
    fun `Flask route decorator with explicit GET method matches`() {
        val lines = listOf(
            """@app.route("/api/users", methods=["GET"])""",
            """def get_users():"""
        )
        val ep = endpoint(HttpMethod.GET, "/api/users")
        val matches = scanner.scanLines(lines, listOf(ep))

        assertEquals(1, matches.size)
        assertEquals(HttpMethod.GET, matches[0].endpoint.method)
    }

    // ── 5. Flask @app.route with multiple methods ─────────────────────────────

    @Test
    fun `Flask route decorator with GET and POST returns first matching endpoint`() {
        val lines = listOf(
            """@app.route("/api/users", methods=["GET", "POST"])""",
            """def users():"""
        )
        val getEp  = endpoint(HttpMethod.GET,  "/api/users")
        val postEp = endpoint(HttpMethod.POST, "/api/users")
        val matches = scanner.scanLines(lines, listOf(getEp, postEp))

        assertEquals(1, matches.size, "Expected exactly one match (deduped per def line)")
        assertTrue(
            matches[0].endpoint.method == HttpMethod.GET || matches[0].endpoint.method == HttpMethod.POST,
            "Expected GET or POST from multi-method route"
        )
    }

    // ── 6. Flask @app.route without methods defaults to GET ───────────────────

    @Test
    fun `Flask route decorator without methods defaults to GET`() {
        val lines = listOf(
            """@app.route("/api/items")""",
            """def list_items():"""
        )
        val ep = endpoint(HttpMethod.GET, "/api/items")
        val matches = scanner.scanLines(lines, listOf(ep))

        assertEquals(1, matches.size)
        assertEquals(HttpMethod.GET, matches[0].endpoint.method)
    }

    // ── 7. Flask typed param <int:id> normalised to {id} ─────────────────────

    @Test
    fun `Flask typed path parameter is normalised to OpenAPI format`() {
        val lines = listOf(
            """@app.route("/api/users/<int:user_id>")""",
            """def get_user(user_id):"""
        )
        val ep = endpoint(HttpMethod.GET, "/api/users/{user_id}")
        val matches = scanner.scanLines(lines, listOf(ep))

        assertEquals(1, matches.size)
        assertEquals("/api/users/{user_id}", matches[0].endpoint.path)
    }

    // ── 8. Flask untyped param <name> normalised to {name} ───────────────────

    @Test
    fun `Flask untyped path parameter is normalised to OpenAPI format`() {
        val lines = listOf(
            """@app.route("/api/items/<item_id>/details")""",
            """def item_details(item_id):"""
        )
        val ep = endpoint(HttpMethod.GET, "/api/items/{item_id}/details")
        val matches = scanner.scanLines(lines, listOf(ep))

        assertEquals(1, matches.size)
    }

    // ── 9. `async def` line is the gutter icon target ────────────────────────

    @Test
    fun `async def line is the gutter icon line not the decorator line`() {
        val lines = listOf(
            """@app.get("/api/ping")""",    // line 0 — decorator
            """async def ping():"""         // line 1 — def
        )
        val ep = endpoint(HttpMethod.GET, "/api/ping")
        val matches = scanner.scanLines(lines, listOf(ep))

        assertEquals(1, matches.size)
        assertEquals(1, matches[0].line)
    }

    // ── 10. Regular def (non-async) is also the gutter target ────────────────

    @Test
    fun `regular def line is the gutter icon line`() {
        val lines = listOf(
            """@app.post("/api/users")""",   // line 0
            """def create_user():"""          // line 1
        )
        val ep = endpoint(HttpMethod.POST, "/api/users")
        val matches = scanner.scanLines(lines, listOf(ep))

        assertEquals(1, matches.size)
        assertEquals(1, matches[0].line)
    }

    // ── 11. Stacked decorators: one match per def line ────────────────────────

    @Test
    fun `stacked decorators on same function produce one match per def line`() {
        val lines = listOf(
            """@app.get("/api/users")""",    // line 0
            """@app.post("/api/users")""",   // line 1
            """async def handle_users():"""  // line 2
        )
        val getEp  = endpoint(HttpMethod.GET,  "/api/users")
        val postEp = endpoint(HttpMethod.POST, "/api/users")
        val matches = scanner.scanLines(lines, listOf(getEp, postEp))

        // One match per def line (first decorator wins)
        assertEquals(1, matches.size)
        assertEquals(2, matches[0].line)
    }

    // ── 12. Empty endpoint list returns empty ─────────────────────────────────

    @Test
    fun `empty endpoint list returns empty result`() {
        val lines = listOf(
            """@app.get("/api/users")""",
            """async def get_users():"""
        )
        assertTrue(scanner.scanLines(lines, emptyList()).isEmpty())
    }

    // ── 13. Multi-line Flask route (methods on next line) ─────────────────────

    @Test
    fun `Flask route decorator with methods on continuation line matches`() {
        val lines = listOf(
            """@app.route("/api/users",""",
            """           methods=["POST"])""",
            """def create_user():"""
        )
        val ep = endpoint(HttpMethod.POST, "/api/users")
        val matches = scanner.scanLines(lines, listOf(ep))

        assertEquals(1, matches.size)
        assertEquals(HttpMethod.POST, matches[0].endpoint.method)
    }

    // ── 14. Multiple endpoints in file produce multiple matches ───────────────

    @Test
    fun `multiple decorators in file produce multiple distinct matches`() {
        val lines = listOf(
            """@app.get("/api/users")""",
            """async def get_users():""",
            """""",
            """@app.post("/api/users")""",
            """async def create_user():"""
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

    // ── 15. Flask 2.x @app.get shortcut (same as FastAPI syntax) ─────────────

    @Test
    fun `Flask 2x shortcut decorator matches`() {
        val lines = listOf(
            """@bp.delete("/api/users/<int:user_id>")""",
            """def delete_user(user_id):"""
        )
        val ep = endpoint(HttpMethod.DELETE, "/api/users/{user_id}")
        val matches = scanner.scanLines(lines, listOf(ep))

        assertEquals(1, matches.size)
        assertEquals(HttpMethod.DELETE, matches[0].endpoint.method)
    }
}
