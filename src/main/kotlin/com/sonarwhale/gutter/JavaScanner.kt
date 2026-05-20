package com.sonarwhale.gutter

import com.sonarwhale.model.ApiEndpoint
import com.sonarwhale.model.HttpMethod

/**
 * [LanguageScanner] for Java Spring Boot / Spring MVC files.
 *
 * Matching strategy (pure text/regex, no PSI):
 *  1. Pre-scan the file for @RestController / @Controller classes and their class-level
 *     @RequestMapping prefix (looking backwards from each class declaration, same approach
 *     as [CSharpScanner.buildControllerSegments]).
 *  2. Scan each line for HTTP-verb mapping annotations (@GetMapping, @PostMapping, …) or
 *     method-level @RequestMapping with explicit method=RequestMethod.XXX.
 *  3. Collect the full annotation block (handles multi-line annotations via paren-depth
 *     tracking), locate the method declaration line, extract paths and HTTP methods.
 *  4. Combine class prefix + method path, normalize Spring path parameter constraints
 *     ({id:[0-9]+} → {id}), match against the OpenAPI endpoint list.
 *  5. Return one [ScanMatch] per method declaration line (deduped).
 *
 * Known limitations:
 *  - @RequestMapping on a method without an explicit `method=` is tried against all HTTP
 *    methods — may produce false positives in non-RESTful controller classes.
 *  - @RequestMapping at class level without @RestController / @Controller is ignored.
 */
class JavaScanner : LanguageScanner {

    override val fileExtensions: Set<String> = setOf("java", "kt")

    // ── Precompiled patterns ──────────────────────────────────────────────────

    // @GetMapping, @PostMapping, @PutMapping, @DeleteMapping, @PatchMapping, @HeadMapping, @OptionsMapping
    // Group 1 = verb name,  Group 2 = annotation content inside parens (may be empty / absent)
    private val reVerbMapping = Regex(
        """@(Get|Post|Put|Delete|Patch|Head|Options)Mapping(?:\s*\(([^)]*)\))?""",
        RegexOption.IGNORE_CASE
    )

    // @RequestMapping(...)   — class-level and method-level
    // Group 1 = annotation content inside parens (may be absent for bare @RequestMapping)
    private val reRequestMapping = Regex(
        """@RequestMapping(?:\s*\(([^)]*)\))?""",
        RegexOption.IGNORE_CASE
    )

    // Extracts string literals: "value"
    private val reStringLiteral = Regex(""""([^"]+)"""")

    // RequestMethod.GET / RequestMethod.POST etc. inside method= arg
    private val reRequestMethod = Regex("""RequestMethod\.(\w+)""", RegexOption.IGNORE_CASE)

    // Spring path parameter with optional regex constraint: {name} or {name:[0-9]+}
    private val reSpringParam = Regex("""\{(\w+)(?::[^}]+)?\}""")

    // Detects a class or interface declaration line
    private val reClassDecl = Regex("""\b(?:class|interface)\b""", RegexOption.IGNORE_CASE)

    private val maxLookahead = 12

    // ── Internal types ────────────────────────────────────────────────────────

    private data class ControllerSegment(val startLine: Int, val prefix: String?)

    private data class AnnotationBlock(
        val methods: List<HttpMethod>,
        val paths: List<String>,          // normalised, without leading /
        val declarationLine: Int
    )

    // ── LanguageScanner ───────────────────────────────────────────────────────

    override fun scanLines(lines: List<String>, endpoints: List<ApiEndpoint>): List<ScanMatch> {
        if (endpoints.isEmpty()) return emptyList()

        val segments    = buildControllerSegments(lines)
        val result      = mutableListOf<ScanMatch>()
        val markedLines = mutableSetOf<Int>()

        var i = 0
        while (i < lines.size) {
            val lower = lines[i].lowercase()

            val hasMappingAnnotation  = lower.contains("mapping")
            val hasRequestMappingOnly = lower.contains("@requestmapping") && !hasMappingAnnotation

            if (!hasMappingAnnotation && !hasRequestMappingOnly) { i++; continue }

            val prefix = segments.lastOrNull { it.startLine <= i }?.prefix
            val block  = collectAnnotationBlock(lines, i)

            if (block.methods.isNotEmpty() && block.declarationLine !in markedLines) {
                findEndpoint(block, prefix, endpoints)?.let { ep ->
                    result += ScanMatch(ep, block.declarationLine)
                    markedLines += block.declarationLine
                }
            }

            // Skip to after the declaration so we don't re-process the same lines.
            i = block.declarationLine + 1
        }

        return result
    }

    // ── Annotation block extraction ───────────────────────────────────────────

    /**
     * Starting at [verbLine], collects annotation lines (including multi-line annotations
     * tracked by paren depth) until a method or class declaration is found.
     * Returns an empty-methods block if a class declaration is encountered (those are
     * handled by [buildControllerSegments] and filtered out in [scanLines]).
     */
    private fun collectAnnotationBlock(lines: List<String>, verbLine: Int): AnnotationBlock {
        val annotationText = StringBuilder()
        var declLine       = verbLine
        var parenDepth     = 0

        var i = verbLine
        while (i < minOf(verbLine + maxLookahead, lines.size)) {
            val text    = lines[i]
            val trimmed = text.trim()
            val lower   = trimmed.lowercase()

            when {
                trimmed.isEmpty() -> { /* skip blank lines */ }

                parenDepth > 0 -> {
                    // Inside a multi-line annotation — keep collecting
                    annotationText.append(' ').append(trimmed)
                    parenDepth += trimmed.count { it == '(' } - trimmed.count { it == ')' }
                    parenDepth = maxOf(0, parenDepth)
                }

                lower.startsWith("@") || lower.startsWith("//") ||
                lower.startsWith("/*") || lower.startsWith("*") -> {
                    annotationText.append(' ').append(trimmed)
                    parenDepth += trimmed.count { it == '(' } - trimmed.count { it == ')' }
                    parenDepth = maxOf(0, parenDepth)
                }

                reClassDecl.containsMatchIn(trimmed) -> {
                    // Class / interface declaration — this block belongs to buildControllerSegments
                    return AnnotationBlock(emptyList(), emptyList(), i)
                }

                else -> {
                    declLine = i
                    break
                }
            }
            i++
        }

        val fullText = annotationText.toString()
        val methods  = mutableListOf<HttpMethod>()
        val paths    = mutableListOf<String>()

        // Extract @GetMapping / @PostMapping / etc.
        for (m in reVerbMapping.findAll(fullText)) {
            HttpMethod.fromString(m.groupValues[1])?.let { methods += it }
            extractPaths(m.groupValues.getOrElse(2) { "" }).forEach { paths += it }
        }

        // Extract method-level @RequestMapping (only if no verb mapping was found on same block)
        if (methods.isEmpty()) {
            for (m in reRequestMapping.findAll(fullText)) {
                val args = m.groupValues.getOrElse(1) { "" }
                extractPaths(args).forEach { paths += it }
                reRequestMethod.findAll(args).forEach { mv ->
                    HttpMethod.fromString(mv.groupValues[1])?.let { methods += it }
                }
            }
        }

        return AnnotationBlock(
            methods         = methods.distinct(),
            paths           = paths.map { normalizeParam(it.trimStart('/')) },
            declarationLine = declLine
        )
    }

    // ── Path extraction ───────────────────────────────────────────────────────

    /**
     * Extracts URL path strings from annotation argument text.
     * Accepts positional string literals and those assigned to `value` or `path` keys.
     * Skips named params like `produces=`, `consumes=`, `name=`, etc.
     */
    private fun extractPaths(annotationArgs: String): List<String> {
        if (annotationArgs.isBlank()) return emptyList()
        val result = mutableListOf<String>()
        for (m in reStringLiteral.findAll(annotationArgs)) {
            val value  = m.groupValues[1]
            val before = annotationArgs.substring(0, m.range.first).trimEnd()
            val eqIdx  = before.lastIndexOf('=')
            if (eqIdx < 0) {
                result += value   // positional arg — always a path
            } else {
                // Named arg: extract the key name that precedes '='
                val keyMatch = Regex("""(\w+)\s*$""").find(before.substring(0, eqIdx).trimEnd())
                val key      = keyMatch?.groupValues?.get(1)?.lowercase() ?: ""
                if (key == "value" || key == "path") result += value
                // All other named keys (produces, consumes, name, headers …) are skipped
            }
        }
        return result
    }

    // ── Token / template helpers ──────────────────────────────────────────────

    private fun normalizeParam(path: String): String =
        reSpringParam.replace(path) { "{${it.groupValues[1]}}" }

    // ── Controller segment detection ──────────────────────────────────────────

    /**
     * Pre-scans the file for @RestController / @Controller class declarations and records
     * each with its resolved @RequestMapping prefix (looking backwards from the class line
     * through consecutive annotation/comment/blank lines).
     */
    private fun buildControllerSegments(lines: List<String>): List<ControllerSegment> {
        val segments = mutableListOf<ControllerSegment>()

        for (i in lines.indices) {
            if (!reClassDecl.containsMatchIn(lines[i])) continue

            var isController    = false
            var requestMapping: String? = null

            for (j in i - 1 downTo maxOf(0, i - 20)) {
                val trimmed = lines[j].trim()
                if (trimmed.isEmpty()) continue
                // Stop at non-annotation, non-comment lines (e.g. closing brace of prev class)
                if (!trimmed.startsWith("@") &&
                    !trimmed.startsWith("//") &&
                    !trimmed.startsWith("/*") &&
                    !trimmed.startsWith("*")) break

                val lower = trimmed.lowercase()
                if (lower.contains("restcontroller") ||
                    (lower.contains("@controller") && !lower.contains("controlleradvice"))) {
                    isController = true
                }

                if (requestMapping == null) {
                    reRequestMapping.find(lines[j])?.let { rm ->
                        val args = rm.groupValues.getOrElse(1) { "" }
                        requestMapping = reStringLiteral.find(args)?.groupValues?.get(1)
                    }
                }
            }

            if (!isController && requestMapping == null) continue

            val prefix = requestMapping?.let { normalizeParam(it.trimStart('/')).lowercase() }
            segments += ControllerSegment(i, prefix)
        }

        return segments
    }

    // ── Endpoint matching ─────────────────────────────────────────────────────

    private fun findEndpoint(
        block: AnnotationBlock,
        prefix: String?,
        endpoints: List<ApiEndpoint>
    ): ApiEndpoint? {
        // If no explicit HTTP method, try all methods from the endpoint list.
        val methodsToTry = block.methods.ifEmpty { endpoints.map { it.method }.distinct() }

        for (method in methodsToTry) {
            val candidates = endpoints.filter { it.method == method }
            if (candidates.isEmpty()) continue

            if (block.paths.isNotEmpty()) {
                for (path in block.paths) {
                    findByPath(path, prefix, candidates)?.let { return it }
                }
            } else {
                // No method path: the controller prefix is the full route.
                findByPath("", prefix, candidates)?.let { return it }
            }
        }
        return null
    }

    private fun findByPath(
        methodPath: String,
        prefix: String?,
        candidates: List<ApiEndpoint>
    ): ApiEndpoint? {
        if (prefix != null) {
            val fullPath = if (methodPath.isEmpty()) prefix
                          else normalizeParam("$prefix/$methodPath").lowercase()
            matchCandidates(fullPath, candidates)?.let { return it }
        }
        if (methodPath.isNotEmpty()) {
            matchCandidates(methodPath.lowercase(), candidates)?.let { return it }
        }
        return null
    }
}
