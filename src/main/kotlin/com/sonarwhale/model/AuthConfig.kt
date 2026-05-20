package com.sonarwhale.model

data class AuthConfig(
    val mode: AuthMode = AuthMode.INHERIT,
    // BEARER
    val bearerToken: String = "",
    // BASIC
    val basicUsername: String = "",
    val basicPassword: String = "",
    // API_KEY
    val apiKeyName: String = "",
    val apiKeyValue: String = "",
    val apiKeyLocation: ApiKeyLocation = ApiKeyLocation.HEADER,
    // OAUTH2_CLIENT_CREDENTIALS
    val oauthTokenUrl: String = "",
    val oauthClientId: String = "",
    val oauthClientSecret: String = "",
    val oauthScope: String = "",
    val oauthGrantType: String = "client_credentials"
)
