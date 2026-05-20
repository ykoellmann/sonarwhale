package com.sonarwhale.gutter

import com.sonarwhale.model.ApiEndpoint
import com.sonarwhale.model.HttpMethod

/**
 * [LanguageScanner] for Python web framework files (FastAPI, Flask, Flask 2.x, Quart).
 *
 * Matching strategy (pure text/regex, no AST):
 *  1. Scan each line for HTTP-verb decorators (@app.get, @router.post, …) or
 *     Flask route decorators (@app.route, @bp.route, …).
 *  2. For each verb decorator, extract the path from the first string literal on that line.
 *  3. For @route decorators, scan forward up to [maxLookahead] lines to collect
 *     methods=[…] (may span a continuation line) and locate the def/async def line.
 *  4. Normalize Flask path params (<int:id> → {id}, <id> → {id}) to OpenAPI form.
 *  5. Emit one [ScanMatch] per def line (deduped — first decorator wins for the def line).
 *
 * Known limitations:
 *  - APIRouter(prefix=...) and Blueprint(url_prefix=...) prefix chaining is not tracked.
 *    Cross-line variable assignment tracking is incompatible with a pure line scanner.
 *    Suffix/contains fallback in matching compensates in most cases.
 *  - `path=` keyword arg is only recognised when it appears as the very first arg; other
 *    keyword-arg orderings are not supported.
 */
class PythonScanner : LanguageScanner {

    override val fileExtensions: Set<String> = setOf("py")

    // ── Precompiled patterns ──────────────────────────────────────────────────

    // @anything.get(  @anything.post(  etc.  — group 1 = verb
    private val reVerbDecorator = Regex(
        """@\w+\.(get|post|put|delete|patch|head|options)\s*\(""",
        RegexOption.IGNORE_CASE
    )

    // @anything.route(
    private val reRouteDecorator = Regex(
        """@\w+\.route\s*\(""",
        RegexOption.IGNORE_CASE
    )

    // First string literal on a line (single or double quotes, no multi-line)
    private val reFirstString = Regex("""["']([^"'\n]+)["']""")

    // methods=["GET", "POST"] inside Flask route args (may span joined lines)
    private val reMethodsList = Regex("""methods\s*=\s*\[([^\]]+)\]""", RegexOption.IGNORE_CASE)
    private val reMethodItem  = Regex("""["'](\w+)["']""")

    // Flask path params: <int:name>, <string:name>, <float:name>, bare <name>
    private val reFlaskParam = Regex("""<(?:\w+:)?(\w+)>""")

    // OpenAPI/FastAPI params with optional constraint: {name} or {name:type}
    private val reOpenApiParam = Regex("""\{(\w+)(?:[=:][^}]*)?\}""")

    private val maxLookahead = 10

    // ── LanguageScanner ───────────────────────────────────────────────────────

    override fun scanLines(lines: List<String>, endpoints: List<ApiEndpoint>): List<ScanMatch> {
        if (endpoints.isEmpty()) return emptyList()

        val result      = mutableListOf<ScanMatch>()
        val markedLines = mutableSetOf<Int>()   // one match per def line

        var i = 0
        while (i < lines.size) {
            val line    = lines[i]
            val trimmed = line.trim()

            if (!trimmed.startsWith("@")) { i++; continue }

            // ── FastAPI / Flask 2.x @x.get("/path") verb shortcuts ────────────
            val verbMatch = reVerbDecorator.find(line)
            if (verbMatch != null) {
                val method = HttpMethod.fromString(verbMatch.groupValues[1])
                if (method != null) {
                    val rawPath = reFirstString.find(line, verbMatch.range.last)
                        ?.groupValues?.get(1)
                    if (rawPath != null) {
                        val defLine = findDefLine(lines, i + 1)
                        if (defLine != -1 && defLine !in markedLines) {
                            val template = normalizePath(rawPath)
                            matchEndpoint(template, listOf(method), endpoints)?.let { ep ->
                                result += ScanMatch(ep, defLine)
                                markedLines += defLine
                            }
                        }
                    }
                }
                i++
                continue
            }

            // ── Flask @x.route("/path", methods=[…]) ─────────────────────────
            val routeMatch = reRouteDecorator.find(line)
            if (routeMatch != null) {
                // The path must be the first string literal after the opening paren.
                val rawPath = reFirstString.find(line, routeMatch.range.last)
                    ?.groupValues?.get(1)
                if (rawPath != null) {
                    // Collect continuation lines until def line to find methods=[...]
                    val contextParts = mutableListOf(line)
                    var defLine = -1

                    for (j in i + 1 until minOf(i + maxLookahead, lines.size)) {
                        val jTrimmed = lines[j].trim()
                        if (isDefLine(jTrimmed)) { defLine = j; break }
                        // Stop collecting if we hit a new decorator that is NOT a continuation
                        if (jTrimmed.startsWith("@") && !jTrimmed.startsWith("@app.route") && defLine == -1) {
                            defLine = j  // treat whatever follows as the "boundary" — no def found
                            defLine = -1
                            break
                        }
                        contextParts += lines[j]
                    }

                    if (defLine != -1 && defLine !in markedLines) {
                        val context  = contextParts.joinToString(" ")
                        val methods  = extractFlaskMethods(context).ifEmpty { listOf(HttpMethod.GET) }
                        val template = normalizePath(rawPath)
                        matchEndpoint(template, methods, endpoints)?.let { ep ->
                            result += ScanMatch(ep, defLine)
                            markedLines += defLine
                        }
                    }
                }
                i++
                continue
            }

            i++
        }

        return result
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun findDefLine(lines: List<String>, startLine: Int): Int {
        for (i in startLine until minOf(startLine + maxLookahead, lines.size)) {
            if (isDefLine(lines[i].trim())) return i
        }
        return -1
    }

    private fun isDefLine(trimmed: String): Boolean =
        trimmed.startsWith("def ") || trimmed.startsWith("async def ")

    private fun extractFlaskMethods(context: String): List<HttpMethod> {
        val listMatch = reMethodsList.find(context) ?: return emptyList()
        return reMethodItem.findAll(listMatch.groupValues[1])
            .mapNotNull { HttpMethod.fromString(it.groupValues[1]) }
            .toList()
    }

    /** Converts Flask path params and any OpenAPI params with constraints to plain {name} form. */
    private fun normalizePath(raw: String): String {
        val afterFlask = reFlaskParam.replace(raw) { "{${it.groupValues[1]}}" }
        return reOpenApiParam.replace(afterFlask) { "{${it.groupValues[1]}}" }.trimStart('/')
    }

    private fun matchEndpoint(
        template: String,
        methods: List<HttpMethod>,
        endpoints: List<ApiEndpoint>
    ): ApiEndpoint? {
        for (method in methods) {
            matchCandidates(template.lowercase(), endpoints.filter { it.method == method })
                ?.let { return it }
        }
        return null
    }
}
