package com.sonarwhale.model

/** Shared config block carried at every level of the hierarchy tree. */
data class HierarchyConfig(
    val variables: List<VariableEntry> = emptyList(),
    val auth: AuthConfig = AuthConfig()
)
