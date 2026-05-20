package com.sonarwhale.model

enum class HttpMethod {
    GET, POST, PUT, DELETE, PATCH, HEAD, OPTIONS;

    companion object {
        fun fromString(name: String): HttpMethod? =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }
}

enum class ParameterLocation {
    PATH, QUERY, HEADER, COOKIE
}

enum class AuthType {
    NONE, BEARER, API_KEY, BASIC, OAUTH2
}

enum class EndpointSource {
    OPENAPI_SERVER, OPENAPI_FILE, OPENAPI_STATIC
}

enum class EndpointStatus {
    ACTIVE, ADDED, MODIFIED, REMOVED
}

enum class AuthMode {
    INHERIT,
    NONE,
    BEARER,
    BASIC,
    API_KEY,
    OAUTH2_CLIENT_CREDENTIALS
}

enum class ApiKeyLocation { HEADER, QUERY }
