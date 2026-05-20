package com.sonarwhale.model

/** Shared config block carried at every level of the hierarchy tree. */
data class HierarchyConfig(
    val variables: List<VariableEntry> = emptyList(),
    val auth: AuthConfig = AuthConfig(),
    /** Names of ScriptLevel values whose scripts are suppressed at this level (pre-phase). */
    val disabledPreLevels: Set<String> = emptySet(),
    /** Names of ScriptLevel values whose scripts are suppressed at this level (post-phase). */
    val disabledPostLevels: Set<String> = emptySet()
)
