package com.sonarwhale.gutter

import com.sonarwhale.model.ApiEndpoint
import com.sonarwhale.model.EndpointSource
import com.sonarwhale.model.HttpMethod
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class JavaScannerTest {

    private val scanner = JavaScanner()

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

    // ── 1. @GetMapping("/path") basic match ───────────────────────────────────

    @Test
    fun `GetMapping with explicit path matches GET endpoint`() {
        val lines = listOf(
            """    @GetMapping("/api/users")""",
            """    public List<User> getAll() { }"""
        )
        val ep = endpoint(HttpMethod.GET, "/api/users")
        val matches = scanner.scanLines(lines, listOf(ep))

        assertEquals(1, matches.size)
        assertEquals(HttpMethod.GET, matches[0].endpoint.method)
        assertEquals("/api/users", matches[0].endpoint.path)
    }

    // ── 2. @PostMapping matches POST but not GET ──────────────────────────────

    @Test
    fun `PostMapping matches POST but not GET endpoint`() {
        val lines = listOf(
            """    @PostMapping("/api/users")""",
            """    public User create(@RequestBody User user) { }"""
        )
        val getEp  = endpoint(HttpMethod.GET,  "/api/users")
        val postEp = endpoint(HttpMethod.POST, "/api/users")
        val matches = scanner.scanLines(lines, listOf(getEp, postEp))

        assertEquals(1, matches.size)
        assertEquals(HttpMethod.POST, matches[0].endpoint.method)
    }

    // ── 3. @RestController + @RequestMapping prefix + @GetMapping ────────────

    @Test
    fun `RestController RequestMapping prefix combined with GetMapping path resolves full route`() {
        val lines = listOf(
            """@RestController""",
            """@RequestMapping("/api/users")""",
            """public class UserController {""",
            """    @GetMapping("/{id}")""",
            """    public User getById(@PathVariable Long id) { }""",
            """}"""
        )
        val ep = endpoint(HttpMethod.GET, "/api/users/{id}")
        val matches = scanner.scanLines(lines, listOf(ep))

        assertEquals(1, matches.size)
        assertEquals("/api/users/{id}", matches[0].endpoint.path)
    }

    // ── 4. @GetMapping with no path uses controller prefix only ───────────────

    @Test
    fun `GetMapping without path uses controller prefix as the full route`() {
        val lines = listOf(
            """@RestController""",
            """@RequestMapping("/api/users")""",
            """public class UserController {""",
            """    @GetMapping""",
            """    public List<User> getAll() { }""",
            """}"""
        )
        val ep = endpoint(HttpMethod.GET, "/api/users")
        val matches = scanner.scanLines(lines, listOf(ep))

        assertEquals(1, matches.size)
        assertEquals("/api/users", matches[0].endpoint.path)
    }

    // ── 5. @RequestMapping(value=…, method=RequestMethod.GET) ─────────────────

    @Test
    fun `RequestMapping with value and method attribute matches correctly`() {
        val lines = listOf(
            """    @RequestMapping(value = "/api/search", method = RequestMethod.GET)""",
            """    public List<User> search(@RequestParam String q) { }"""
        )
        val ep = endpoint(HttpMethod.GET, "/api/search")
        val matches = scanner.scanLines(lines, listOf(ep))

        assertEquals(1, matches.size)
        assertEquals(HttpMethod.GET, matches[0].endpoint.method)
    }

    // ── 6. @RequestMapping with method=DELETE and controller prefix ───────────

    @Test
    fun `RequestMapping with DELETE method resolves with controller prefix`() {
        val lines = listOf(
            """@RestController""",
            """@RequestMapping("/api/users")""",
            """public class UserController {""",
            """    @RequestMapping(value = "/{id}", method = RequestMethod.DELETE)""",
            """    public void delete(@PathVariable Long id) { }""",
            """}"""
        )
        val ep = endpoint(HttpMethod.DELETE, "/api/users/{id}")
        val matches = scanner.scanLines(lines, listOf(ep))

        assertEquals(1, matches.size)
        assertEquals(HttpMethod.DELETE, matches[0].endpoint.method)
    }

    // ── 7. Path parameter {id} matches OpenAPI endpoint ───────────────────────

    @Test
    fun `Spring path parameter matches OpenAPI endpoint`() {
        val lines = listOf(
            """    @PutMapping("/api/items/{id}")""",
            """    public Item update(@PathVariable Long id, @RequestBody Item item) { }"""
        )
        val ep = endpoint(HttpMethod.PUT, "/api/items/{id}")
        val matches = scanner.scanLines(lines, listOf(ep))

        assertEquals(1, matches.size)
        assertEquals("/api/items/{id}", matches[0].endpoint.path)
    }

    // ── 8. Path constraint {id:[0-9]+} normalised to {id} ────────────────────

    @Test
    fun `Spring path parameter regex constraint is normalised for matching`() {
        val lines = listOf(
            """    @GetMapping("/api/items/{id:[0-9]+}")""",
            """    public Item get(@PathVariable Long id) { }"""
        )
        val ep = endpoint(HttpMethod.GET, "/api/items/{id}")
        val matches = scanner.scanLines(lines, listOf(ep))

        assertEquals(1, matches.size)
        assertEquals("/api/items/{id}", matches[0].endpoint.path)
    }

    // ── 9. @GetMapping({"path1", "path2"}) tries all paths ───────────────────

    @Test
    fun `GetMapping with multiple paths matches on any listed path`() {
        val lines = listOf(
            """    @GetMapping({"/api/users", "/api/members"})""",
            """    public List<User> getAll() { }"""
        )
        val ep = endpoint(HttpMethod.GET, "/api/users")
        val matches = scanner.scanLines(lines, listOf(ep))

        assertEquals(1, matches.size)
        assertEquals("/api/users", matches[0].endpoint.path)
    }

    // ── 10. Class without @RestController is not treated as controller ─────────

    @Test
    fun `class without RestController annotation does not contribute a prefix`() {
        val lines = listOf(
            """public class UserService {""",
            """    @GetMapping("/api/users")""",
            """    public List<User> getAll() { }""",
            """}"""
        )
        val ep = endpoint(HttpMethod.GET, "/api/users")
        val matches = scanner.scanLines(lines, listOf(ep))

        // Match is still found by direct path (no prefix applied), so the endpoint is matched.
        assertEquals(1, matches.size)
    }

    // ── 11. @Controller (not @RestController) is also recognised ──────────────

    @Test
    fun `Controller annotation is recognised as a controller class`() {
        val lines = listOf(
            """@Controller""",
            """@RequestMapping("/api/pages")""",
            """public class PageController {""",
            """    @GetMapping("/list")""",
            """    public String list(Model model) { }""",
            """}"""
        )
        val ep = endpoint(HttpMethod.GET, "/api/pages/list")
        val matches = scanner.scanLines(lines, listOf(ep))

        assertEquals(1, matches.size)
        assertEquals("/api/pages/list", matches[0].endpoint.path)
    }

    // ── 12. Method declaration line is the gutter icon target ─────────────────

    @Test
    fun `method declaration line is the gutter icon line not the annotation line`() {
        val lines = listOf(
            """    @DeleteMapping("/api/users/{id}")""",   // line 0 — annotation
            """    public void delete(@PathVariable Long id) { }"""   // line 1 — declaration
        )
        val ep = endpoint(HttpMethod.DELETE, "/api/users/{id}")
        val matches = scanner.scanLines(lines, listOf(ep))

        assertEquals(1, matches.size)
        assertEquals(1, matches[0].line)
    }

    // ── 13. Multiple mappings in a class produce multiple matches ─────────────

    @Test
    fun `multiple mappings in a class produce multiple distinct matches`() {
        val lines = listOf(
            """@RestController""",
            """@RequestMapping("/api/users")""",
            """public class UserController {""",
            """    @GetMapping""",
            """    public List<User> getAll() { }""",
            """    @PostMapping""",
            """    public User create(@RequestBody User user) { }""",
            """}"""
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

    // ── 14. Empty endpoint list returns empty ─────────────────────────────────

    @Test
    fun `empty endpoint list returns empty result`() {
        val lines = listOf(
            """    @GetMapping("/api/users")""",
            """    public List<User> getAll() { }"""
        )
        assertTrue(scanner.scanLines(lines, emptyList()).isEmpty())
    }

    // ── 15. Multi-line @RequestMapping annotation ─────────────────────────────

    @Test
    fun `multi-line RequestMapping annotation is parsed correctly`() {
        val lines = listOf(
            """    @RequestMapping(""",
            """        value = "/api/search",""",
            """        method = RequestMethod.POST""",
            """    )""",
            """    public SearchResult search(@RequestBody SearchRequest req) { }"""
        )
        val ep = endpoint(HttpMethod.POST, "/api/search")
        val matches = scanner.scanLines(lines, listOf(ep))

        assertEquals(1, matches.size)
        assertEquals(HttpMethod.POST, matches[0].endpoint.method)
    }

    // ── 16. @PatchMapping and @DeleteMapping work ─────────────────────────────

    @Test
    fun `PatchMapping and DeleteMapping are both supported`() {
        val lines = listOf(
            """    @PatchMapping("/api/users/{id}")""",
            """    public User patch(@PathVariable Long id) { }""",
            """    @DeleteMapping("/api/users/{id}")""",
            """    public void delete(@PathVariable Long id) { }"""
        )
        val endpoints = listOf(
            endpoint(HttpMethod.PATCH,  "/api/users/{id}"),
            endpoint(HttpMethod.DELETE, "/api/users/{id}")
        )
        val matches = scanner.scanLines(lines, endpoints)

        assertEquals(2, matches.size)
        val methods = matches.map { it.endpoint.method }.toSet()
        assertTrue(HttpMethod.PATCH  in methods)
        assertTrue(HttpMethod.DELETE in methods)
    }
}
