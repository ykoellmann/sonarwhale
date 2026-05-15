package com.sonarwhale.model

data class SonarwhaleEnvironment(
    val id: String,
    val name: String,                      // z.B. "dev", "staging", "prod"
    val source: EnvironmentSource,
    val isActive: Boolean = false,
    val sourceAuth: AuthConfig = AuthConfig(mode = AuthMode.NONE)
)

sealed class EnvironmentSource {

    /** Option A: Server-URL + Port, OpenAPI wird per HTTP gefetcht. */
    data class ServerUrl(
        val host: String,                  // z.B. "http://localhost" or "localhost" or "127.0.0.1"
        val port: Int,                     // z.B. 5000
        val openApiPath: String? = null    // null → Auto-Discovery
    ) : EnvironmentSource() {
        /** Always returns a fully-qualified base URL with scheme, e.g. "http://127.0.0.1:57293". */
        val baseUrl: String get() = "${normalizedHost()}:$port"
        val openApiUrl: String? get() = openApiPath?.let { "${normalizedHost()}:$port$it" }

        /** Prepends "http://" if the user omitted the scheme. */
        private fun normalizedHost(): String {
            val h = host.trim()
            return if (h.startsWith("http://") || h.startsWith("https://")) h else "http://$h"
        }
    }

    /** Option B: Lokaler Dateipfad zur OpenAPI JSON/YAML-Datei. */
    data class FilePath(
        val path: String                   // z.B. "./bin/Debug/net8.0/swagger.json"
    ) : EnvironmentSource()

    /** Option C: Statisch importierter JSON-Inhalt (kein Auto-Refresh). */
    data class StaticImport(
        val cachedContent: String          // gespeicherter JSON-Inhalt
    ) : EnvironmentSource()
}
