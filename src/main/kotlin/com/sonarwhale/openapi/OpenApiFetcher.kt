package com.sonarwhale.openapi

import com.google.gson.JsonParser
import com.sonarwhale.model.ApiKeyLocation
import com.sonarwhale.model.AuthConfig
import com.sonarwhale.model.AuthMode
import com.sonarwhale.model.EndpointSource
import com.sonarwhale.model.EnvironmentSource
import com.sonarwhale.model.SonarwhaleEnvironment
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Paths
import java.time.Duration
import java.util.Base64

/**
 * Fetcht OpenAPI-Daten für ein Environment asynchron.
 * Rückgabe: (json: String?, fromCache: Boolean)
 * - json == null → Fehler, kein Cache verfügbar
 * - fromCache == true → Daten kommen aus dem Fallback-Cache
 */
object OpenApiFetcher {

    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    // OAuth2 token cache for source auth: "tokenUrl|clientId" → (token, expiresAtMs)
    private val tokenCache = mutableMapOf<String, Pair<String, Long>>()

    /**
     * Führt den Fetch für das gegebene Environment durch.
     * Blockierend — muss aus einem Background-Thread aufgerufen werden.
     *
     * @param env Das Environment
     * @param cachedJson Fallback-Inhalt wenn Quelle nicht erreichbar (kann null sein)
     * @return FetchResult
     */
    fun fetch(env: SonarwhaleEnvironment, cachedJson: String?): FetchResult {
        return when (val src = env.source) {
            is EnvironmentSource.ServerUrl -> fetchFromServer(src, cachedJson, env.sourceAuth)
            is EnvironmentSource.FilePath  -> fetchFromFile(src, cachedJson)
            is EnvironmentSource.StaticImport -> FetchResult.Success(src.cachedContent, EndpointSource.OPENAPI_STATIC)
        }
    }

    // -------------------------------------------------------------------------
    // Option A: Server-URL
    // -------------------------------------------------------------------------

    private fun fetchFromServer(src: EnvironmentSource.ServerUrl, cachedJson: String?, auth: AuthConfig): FetchResult {
        val baseUrl = src.baseUrl.trimEnd('/')

        // Manueller Pfad-Override hat Vorrang
        val overrideUrl = src.openApiUrl
        if (overrideUrl != null) {
            return tryHttpGet(overrideUrl, EndpointSource.OPENAPI_SERVER, cachedJson, auth)
        }

        // Auto-Discovery: bekannte Pfade der Reihe nach probieren
        for (path in OpenApiDiscovery.knownPaths) {
            val result = tryHttpGet("$baseUrl$path", EndpointSource.OPENAPI_SERVER, cachedJson = null, auth)
            if (result is FetchResult.Success) return result
        }

        return if (cachedJson != null)
            FetchResult.Cached(cachedJson, EndpointSource.OPENAPI_SERVER)
        else
            FetchResult.Error("Keine erreichbare OpenAPI-Quelle gefunden unter $baseUrl")
    }

    private fun tryHttpGet(url: String, source: EndpointSource, cachedJson: String?, auth: AuthConfig): FetchResult {
        val resolvedUrl = applyQueryAuth(url, auth)
        return runCatching {
            val builder = HttpRequest.newBuilder()
                .uri(URI.create(resolvedUrl))
                .timeout(Duration.ofSeconds(8))
                .GET()
            applyHeaderAuth(builder, auth)
            val resp = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString())
            if (resp.statusCode() in 200..299) {
                FetchResult.Success(resp.body(), source)
            } else {
                if (cachedJson != null) FetchResult.Cached(cachedJson, source)
                else FetchResult.Error("HTTP ${resp.statusCode()} von $url")
            }
        }.getOrElse { e ->
            if (cachedJson != null) FetchResult.Cached(cachedJson, source)
            else FetchResult.Error("Verbindungsfehler: ${e.message}")
        }
    }

    private fun applyHeaderAuth(builder: HttpRequest.Builder, auth: AuthConfig) {
        when (auth.mode) {
            AuthMode.BEARER ->
                builder.header("Authorization", "Bearer ${auth.bearerToken}")
            AuthMode.BASIC -> {
                val encoded = Base64.getEncoder()
                    .encodeToString("${auth.basicUsername}:${auth.basicPassword}".toByteArray())
                builder.header("Authorization", "Basic $encoded")
            }
            AuthMode.API_KEY -> if (auth.apiKeyLocation == ApiKeyLocation.HEADER)
                builder.header(auth.apiKeyName, auth.apiKeyValue)
            AuthMode.OAUTH2_CLIENT_CREDENTIALS ->
                getOrFetchToken(auth)?.let { builder.header("Authorization", "Bearer $it") }
            else -> {}
        }
    }

    private fun applyQueryAuth(url: String, auth: AuthConfig): String {
        if (auth.mode != AuthMode.API_KEY || auth.apiKeyLocation != ApiKeyLocation.QUERY) return url
        val sep = if (url.contains('?')) '&' else '?'
        return "$url$sep${auth.apiKeyName}=${auth.apiKeyValue}"
    }

    private fun getOrFetchToken(auth: AuthConfig): String? {
        val cacheKey = "${auth.oauthTokenUrl}|${auth.oauthClientId}"
        val cached = tokenCache[cacheKey]
        if (cached != null && System.currentTimeMillis() < cached.second) return cached.first

        return runCatching {
            val body = buildString {
                append("grant_type=client_credentials")
                append("&client_id=${auth.oauthClientId}")
                append("&client_secret=${auth.oauthClientSecret}")
                if (auth.oauthScope.isNotBlank()) append("&scope=${auth.oauthScope}")
            }
            val req = HttpRequest.newBuilder()
                .uri(URI.create(auth.oauthTokenUrl))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
            val resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString())
            val json = JsonParser.parseString(resp.body()).asJsonObject
            val token = json.get("access_token")?.asString ?: return@runCatching null
            val expiresIn = json.get("expires_in")?.asLong ?: 3600L
            tokenCache[cacheKey] = Pair(token, System.currentTimeMillis() + (expiresIn - 30) * 1000)
            token
        }.getOrNull()
    }

    // -------------------------------------------------------------------------
    // Option B: Dateipfad
    // -------------------------------------------------------------------------

    private fun fetchFromFile(src: EnvironmentSource.FilePath, cachedJson: String?): FetchResult {
        return runCatching {
            val content = Files.readString(Paths.get(src.path))
            FetchResult.Success(content, EndpointSource.OPENAPI_FILE)
        }.getOrElse { e ->
            if (cachedJson != null) FetchResult.Cached(cachedJson, EndpointSource.OPENAPI_FILE)
            else FetchResult.Error("Datei nicht lesbar: ${e.message}")
        }
    }

    // -------------------------------------------------------------------------
    // Ergebnis-Typen
    // -------------------------------------------------------------------------

    sealed class FetchResult {
        /** Frische Daten direkt von der Quelle. */
        data class Success(val json: String, val source: EndpointSource) : FetchResult()
        /** Daten aus dem Fallback-Cache (Quelle war nicht erreichbar). */
        data class Cached(val json: String, val source: EndpointSource) : FetchResult()
        /** Fehler — auch kein Cache verfügbar. */
        data class Error(val message: String) : FetchResult()
    }
}
