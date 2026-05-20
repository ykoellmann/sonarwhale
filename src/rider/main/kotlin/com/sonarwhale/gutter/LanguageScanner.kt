package com.sonarwhale.gutter

import com.sonarwhale.model.ApiEndpoint

/** One match: the endpoint this source line corresponds to, and which line the gutter icon goes on. */
data class ScanMatch(val endpoint: ApiEndpoint, val line: Int)

/**
 * Language-specific scanner that maps source-file lines to [ApiEndpoint] matches.
 *
 * Implement this interface to add gutter-icon and source-navigation support for a new language
 * (e.g. Python/FastAPI, Java/Spring Boot). Register the implementation in
 * [SonarwhaleGutterService.scanners].
 *
 * Implementations must be stateless and pure — no I/O, no side effects.
 */
/**
 * Fuzzy endpoint matching cascade shared by all [LanguageScanner] implementations.
 *
 * [template] must already be normalized by the caller (language-specific param stripping,
 * trimStart('/'), lowercase). [candidates] must already be filtered to a single HTTP method.
 *
 * Match priority: exact → suffix → reverse-suffix → contains/reverse-contains.
 */
fun matchCandidates(template: String, candidates: List<ApiEndpoint>): ApiEndpoint? {
    if (template.isEmpty() || candidates.isEmpty()) return null
    fun norm(path: String) = path.trimStart('/').lowercase()
    val normalized = candidates.associateWith { norm(it.path) }
    return normalized.entries.firstOrNull { (_, n) -> n == template }?.key
        ?: normalized.entries.firstOrNull { (_, n) -> n.endsWith(template) }?.key
        ?: normalized.entries.firstOrNull { (_, n) -> template.endsWith(n) }?.key
        ?: normalized.entries.firstOrNull { (_, n) -> n.contains(template) || template.contains(n) }?.key
}

interface LanguageScanner {

    /**
     * File extensions this scanner handles, lowercase, without the leading dot.
     * Examples: `setOf("cs")`, `setOf("py")`, `setOf("java", "kt")`.
     */
    val fileExtensions: Set<String>

    /**
     * Scan [lines] from a single source file and return all (endpoint, icon-line) pairs.
     *
     * @param lines     raw text lines of the source file (no trailing newlines)
     * @param endpoints current OpenAPI endpoint list to match against
     */
    fun scanLines(lines: List<String>, endpoints: List<ApiEndpoint>): List<ScanMatch>
}
