package com.sonarwhale.model

import java.util.UUID

data class SavedRequest(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "Default",
    val isDefault: Boolean = false,
    val headers: String = "",                       // JSON array of NameValueRow (non-auth headers)
    val body: String = "",
    val bodyMode: String = "none",                  // "none" | "form-data" | "raw" | "binary"
    val bodyContentType: String = "application/json",
    val paramValues: Map<String, String> = emptyMap(),
    val paramEnabled: Map<String, Boolean> = emptyMap(),
    val config: HierarchyConfig = HierarchyConfig()
)
